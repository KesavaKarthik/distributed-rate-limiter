package com.ratelimiter.distributed;

import com.ratelimiter.RateLimiterServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sliding window counter across two instances.
 *
 * <p>Two claims: the read-blend-write is atomic (concurrency test), and the
 * previous window's decay kills the fixed-window boundary burst (boundary test).
 */
@Testcontainers(disabledWithoutDocker = true)
class SlidingWindowCounterTest {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowCounterTest.class);

    private static final int LIMIT = 20;
    private static final long WINDOW_MS = 6_000;
    private static final int TOTAL_REQUESTS = 200;

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
        instanceA = bootInstance("swc-instance-A");
        instanceB = bootInstance("swc-instance-B");
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
        return new SpringApplicationBuilder(RateLimiterServiceApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.application.name=" + name,
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--ratelimiter.algorithm=swc",
                        "--ratelimiter.limit=" + LIMIT,
                        "--ratelimiter.window-ms=" + WINDOW_MS);
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @Test
    void concurrentRequestsAcrossTwoInstances_admitExactlyLimit() throws Exception {
        String clientId = "swc-concurrency";
        get(portA, "warmup");
        get(portB, "warmup");
        redis().delete("ratelimit:swc:user:" + clientId);

        // Start at a window boundary so the burst cannot straddle one: with a
        // fresh key `previous` is 0, so the estimate is just the current count
        // and the expected result is exact.
        sleepUntilNextWindowStart();

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

        Map<Object, Object> state = redis().opsForHash().entries("ratelimit:swc:user:" + clientId);
        log.warn("""
                        SLIDING WINDOW COUNTER - CONCURRENCY
                          limit             : {}
                          requests fired    : {} (split across 2 instances)
                          ALLOWED (HTTP 200): {}   <-- must be exactly {}, overshoot = {}
                          DENIED  (HTTP 429): {}
                          hash in redis     : {}   <-- O(1) state, whatever the traffic""",
                LIMIT, TOTAL_REQUESTS, allowed.get(), LIMIT, allowed.get() - LIMIT,
                denied.get(), state);

        assertTrue(finished, "requests did not complete in time");
        assertEquals(0, unexpected.get(), "every request should answer 200 or 429");

        // With previous = 0 the estimate is exactly the current count, so the
        // approximation costs nothing here and the assertion can be exact. Any
        // overshoot means the read-blend-write raced.
        assertEquals(LIMIT, allowed.get(),
                "an atomic sliding window counter must admit EXACTLY the limit inside one window");
        assertEquals(TOTAL_REQUESTS - LIMIT, denied.get());
    }

    /**
     * The fixed-window boundary burst, and why this algorithm exists.
     *
     * <p>Fill the limit late in window N, then ask again just after the boundary
     * into window N+1. A fixed window has reset its counter to zero and admits a
     * second full limit — 2x over a span far shorter than W. Here the previous
     * window still counts at nearly full weight, so almost nothing gets through.
     */
    @Test
    void justAfterAWindowBoundary_doesNotAdmitASecondFullLimit() throws Exception {
        String clientId = "swc-boundary";
        get(portA, "warmup");
        redis().delete("ratelimit:swc:user:" + clientId);

        sleepUntilNextWindowStart();
        int inWindowN = burst(clientId, LIMIT);

        // Cross into window N+1. `current` becomes `previous` and starts decaying
        // from ~1.0, so the estimate is still ~LIMIT immediately after the cross.
        sleepUntilNextWindowStart();
        int justAfterBoundary = burst(clientId, LIMIT);

        Map<Object, Object> state = redis().opsForHash().entries("ratelimit:swc:user:" + clientId);
        log.warn("""
                        SLIDING WINDOW COUNTER - BOUNDARY (limit {} per {}ms)
                          window N   allowed: {}   <-- expect {}
                          window N+1 allowed: {}   <-- a FIXED window would admit {} here (2x)
                          hash in redis     : {}""",
                LIMIT, WINDOW_MS, inWindowN, LIMIT, justAfterBoundary, LIMIT, state);

        assertEquals(LIMIT, inWindowN, "an empty window should admit the full limit");

        // The claim is qualitative on purpose: `previous` decays continuously, so
        // the exact count admitted depends on how long the burst takes. What must
        // hold is that the boundary is NOT a reset.
        assertTrue(justAfterBoundary <= 2,
                "crossing a window boundary must not hand back the budget; a fixed window would "
                        + "have admitted " + LIMIT + " but this admitted " + justAfterBoundary);
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

    /**
     * Parks until just past the next window boundary, measured on REDIS's clock —
     * the same clock the script derives the window id from. Using the test JVM's
     * clock would make the test sensitive to exactly the skew this project
     * designs out.
     */
    private static void sleepUntilNextWindowStart() throws InterruptedException {
        long now = redisNowMs();
        Thread.sleep((WINDOW_MS - (now % WINDOW_MS)) + 100);
    }

    private static long redisNowMs() {
        Long time = redis().execute((RedisCallback<Long>) connection ->
                connection.serverCommands().time());
        if (time == null) {
            throw new IllegalStateException("redis TIME returned nothing");
        }
        return time;
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
