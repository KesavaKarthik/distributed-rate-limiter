package com.ratelimiter.distributed;

import com.ratelimiter.RateLimiterServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sliding window log across two instances.
 *
 * <p>Two claims: the prune-count-add is atomic (concurrency test), and the bound
 * is genuinely rolling rather than per-fixed-window (boundary test). The second
 * is the guarantee a token bucket cannot make.
 */
@Testcontainers(disabledWithoutDocker = true)
class SlidingWindowLogTest {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowLogTest.class);

    private static final int LIMIT = 20;
    private static final long WINDOW_MS = 5_000;
    private static final int TOTAL_REQUESTS = 200; // deliberately >> limit

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;
    private static int portA;
    private static int portB;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    static void startBothInstances() {
        instanceA = bootInstance("swl-instance-A");
        instanceB = bootInstance("swl-instance-B");
        portA = portOf(instanceA);
        portB = portOf(instanceB);
    }

    @AfterAll
    static void stopBothInstances() {
        if (instanceA != null) {
            instanceA.close();
        }
        if (instanceB != null) {
            instanceB.close();
        }
    }

    private static ConfigurableApplicationContext bootInstance(String name) {
        // Command-line args, not .properties(): default properties rank BELOW
        // application.yml, so the yml's values would silently win.
        return new SpringApplicationBuilder(RateLimiterServiceApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.application.name=" + name,
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--ratelimiter.algorithm=swl",
                        "--ratelimiter.limit=" + LIMIT,
                        "--ratelimiter.window-ms=" + WINDOW_MS);
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @Test
    void concurrentRequestsAcrossTwoInstances_admitExactlyLimit() throws Exception {
        String clientId = "swl-concurrency";
        get(portA, "warmup");
        get(portB, "warmup");
        redis().delete("ratelimit:swl:user:" + clientId);

        ExecutorService executor = Executors.newFixedThreadPool(TOTAL_REQUESTS);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(TOTAL_REQUESTS);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            int port = (i % 2 == 0) ? portA : portB;
            executor.submit(() -> {
                try {
                    startSignal.await();
                    int status = get(port, clientId);
                    if (status == 200) {
                        allowed.incrementAndGet();
                    } else if (status == 429) {
                        denied.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        startSignal.countDown();
        boolean finished = doneSignal.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        Long logged = redis().opsForZSet().size("ratelimit:swl:user:" + clientId);
        log.warn("""
                        SLIDING WINDOW LOG - CONCURRENCY
                          limit             : {}
                          requests fired    : {} (split across 2 instances)
                          ALLOWED (HTTP 200): {}   <-- must be exactly {}, overshoot = {}
                          DENIED  (HTTP 429): {}
                          zset entries      : {}   <-- only ADMITTED requests are logged""",
                LIMIT, TOTAL_REQUESTS, allowed.get(), LIMIT, allowed.get() - LIMIT,
                denied.get(), logged);

        assertTrue(finished, "requests did not complete in time");
        assertEquals(0, unexpected.get(), "every request should answer 200 or 429");

        // Prune -> count -> add lives in one script, so the two instances are
        // serialized. Split across round trips, both would read count = LIMIT-1
        // and both would add.
        assertEquals(LIMIT, allowed.get(),
                "an atomic sliding window log must admit EXACTLY the limit");
        assertEquals(TOTAL_REQUESTS - LIMIT, denied.get());

        // The ZSET holds one entry per ADMITTED request: O(limit), not O(traffic).
        assertEquals(LIMIT, logged, "denied requests must never be logged");
    }

    /**
     * Burst, wait less than a window, burst again. A fixed window would refill at
     * its reset and admit 2x; the log admits nothing, because the first burst is
     * still inside the trailing window. This is the pattern a token bucket cannot
     * bound either — it would have refilled at its rate during the wait.
     */
    @Test
    void burstThenWaitLessThanWindowThenBurst_holdsTheRollingBound() throws Exception {
        String clientId = "swl-rolling";
        get(portA, "warmup");
        redis().delete("ratelimit:swl:user:" + clientId);

        int firstBurst = burst(clientId, LIMIT);

        Thread.sleep(WINDOW_MS / 2);
        int secondBurst = burst(clientId, LIMIT);

        // Past the window relative to the FIRST burst. The second burst added
        // nothing (denied requests are not logged), so the window is now empty.
        Thread.sleep((WINDOW_MS / 2) + 800);
        int thirdBurst = burst(clientId, LIMIT);

        log.warn("""
                        SLIDING WINDOW LOG - ROLLING BOUND (limit {} per {}ms)
                          burst 1 at t=0        allowed: {}   <-- expect {}
                          burst 2 at t=W/2      allowed: {}   <-- expect 0 (still inside the window)
                          burst 3 at t>W        allowed: {}   <-- expect {} (window slid, nothing carried over)""",
                LIMIT, WINDOW_MS, firstBurst, LIMIT, secondBurst, thirdBurst, LIMIT);

        assertEquals(LIMIT, firstBurst, "an empty window should admit the full limit");

        // The whole point: no boundary to exploit. Half a window later the first
        // burst still counts in full.
        assertEquals(0, secondBurst,
                "a rolling ceiling must admit nothing while the earlier burst is still in-window");

        // Also proves denied requests were never logged — if they had been, the
        // second burst would have pushed the expiry forward and locked the client out.
        assertEquals(LIMIT, thirdBurst,
                "once the first burst ages out, the full limit must be available again");
    }

    /** Fires n sequential requests, returning how many were admitted. */
    private static int burst(String clientId, int n) throws Exception {
        int allowed = 0;
        for (int i = 0; i < n; i++) {
            if (get(portA, clientId) == 200) {
                allowed++;
            }
        }
        return allowed;
    }

    private static StringRedisTemplate redis() {
        return instanceA.getBean(StringRedisTemplate.class);
    }

    private static int get(int port, String clientId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ping"))
                .header("X-Client-Id", clientId)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
