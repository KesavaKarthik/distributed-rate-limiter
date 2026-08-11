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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TokenBucketRaceTest} one level up: two app instances with separate
 * contexts and heaps, sharing one Redis. No JVM lock can help — they have no
 * monitor in common.
 *
 * <p>Against the naive GET-then-SET this same test asserted the OPPOSITE
 * ({@code allowed > capacity}) and passed. Only the assertion changed when the
 * read-modify-write moved into Lua.
 *
 * <p>Scoping caveat: both contexts run in one test JVM, so these are not two OS
 * processes — but the race lives in the gap between a Redis read and write, which
 * two independent contexts reproduce exactly. docker-compose is the real topology.
 */
@Testcontainers(disabledWithoutDocker = true)
class DistributedRaceTest {

    private static final Logger log = LoggerFactory.getLogger(DistributedRaceTest.class);

    private static final int CAPACITY = 100;
    private static final int TOTAL_REQUESTS = 200; // deliberately > capacity
    private static final String CLIENT_ID = "race-victim";
    private static final String BUCKET_KEY = "ratelimit:tb:user:" + CLIENT_ID;

    /** The only common ground the two otherwise-isolated instances have. */
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
        instanceA = bootInstance("instance-A");
        instanceB = bootInstance("instance-B");
        portA = portOf(instanceA);
        portB = portOf(instanceB);
        log.info("instance-A on :{}, instance-B on :{}, sharing redis {}:{}",
                portA, portB, REDIS.getHost(), REDIS.getMappedPort(6379));
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

    /**
     * Boots an instance on a random port against the shared Redis. refill-rate 0
     * keeps tokens from regenerating mid-test, isolating the race to the decrement.
     */
    private static ConfigurableApplicationContext bootInstance(String name) {
        // Passed as COMMAND-LINE ARGS, not via .properties(). That method feeds
        // SpringApplication.setDefaultProperties, which Boot ranks BELOW
        // application.yml — so every value here that the yml also declares
        // (redis host/port, capacity, refill-rate, application name) was
        // silently discarded, and the instances booted against the yml's
        // localhost:6379 with capacity 10 instead of the container and 100.
        // Command-line args outrank application.yml, so these actually apply.
        return new SpringApplicationBuilder(RateLimiterServiceApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.application.name=" + name,
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--ratelimiter.capacity=" + CAPACITY,
                        "--ratelimiter.refill-rate=0.0");
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @Test
    void concurrentRequestsAcrossTwoInstances_allowExactlyCapacity_provingAtomicityClosedTheRace()
            throws Exception {

        // Warm up on a DIFFERENT key so the victim bucket stays untouched. Pays
        // the one-off costs (Lettuce connect, JIT) so the burst actually overlaps
        // instead of queueing behind first-request latency.
        get(portA, "warmup");
        get(portB, "warmup");

        StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
        redis.delete(BUCKET_KEY);

        ExecutorService executor = Executors.newFixedThreadPool(TOTAL_REQUESTS);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(TOTAL_REQUESTS);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            // Alternate instances so the load is split evenly across BOTH.
            int port = (i % 2 == 0) ? portA : portB;
            executor.submit(() -> {
                try {
                    startSignal.await(); // every thread parks here...
                    int status = get(port, CLIENT_ID);
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

        startSignal.countDown(); // ...and are released together
        boolean finished = doneSignal.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        Map<Object, Object> finalState = redis.opsForHash().entries(BUCKET_KEY);
        log.warn("""
                        DISTRIBUTED ATOMICITY RESULT
                          capacity          : {}
                          requests fired    : {} (split across 2 instances)
                          ALLOWED (HTTP 200): {}   <-- must be exactly {}, overshoot = {} (0 = race closed)
                          DENIED  (HTTP 429): {}
                          bucket in redis   : {}""",
                CAPACITY, TOTAL_REQUESTS, allowed.get(), CAPACITY,
                allowed.get() - CAPACITY, denied.get(), finalState);

        assertTrue(finished, "requests did not complete in time");
        assertEquals(0, unexpected.get(), "every request should answer 200 or 429");
        assertEquals(TOTAL_REQUESTS, allowed.get() + denied.get());

        // THE POINT: 200 simultaneous requests against 100 tokens let exactly 100
        // through, however the two instances interleave. Under the naive
        // GET-then-SET both instances read the same count and one decrement was
        // lost; the Lua script serializes them, so EXACT is now earned.
        assertEquals(CAPACITY, allowed.get(),
                "an atomic limiter must allow EXACTLY capacity requests; anything more means "
                        + "the read-modify-write is racing again");
        assertEquals(TOTAL_REQUESTS - CAPACITY, denied.get());
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
