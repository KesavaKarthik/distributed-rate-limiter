package com.ratelimiter.distributed;

import com.ratelimiter.RateLimiterServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when the one thing every instance depends on goes away.
 *
 * <p>Phase 1 moved atomicity into Redis, which also moved a hard dependency onto
 * the request path: before Phase 2 an outage propagated out of
 * {@code tryAcquire} as a 500, so the component added to protect the service
 * became the thing that took it down. This test kills Redis mid-flight and
 * asserts that the degraded behaviour is a decision, made per rule, and never a
 * 500.
 *
 * <p>Redis is bound to a fixed host port rather than a mapped one so it can be
 * stopped and restarted without the port the app was configured with going stale
 * underneath it.
 */
@EnabledIf("dockerAvailable")
class FailureModeTest {

    private static final Logger log = LoggerFactory.getLogger(FailureModeTest.class);

    private static final String FAIL_OPEN_PATH = "/api/burst";
    private static final String FAIL_CLOSED_PATH = "/api/strict";

    private static final int REDIS_PORT = freePort();

    private static GenericContainer<?> redis;
    private static ConfigurableApplicationContext app;
    private static int port;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void startRedisAndApp() {
        redis = startRedis();
        app = new SpringApplicationBuilder(RateLimiterServiceApplication.class)
                .run("--server.port=0",
                        "--spring.data.redis.host=localhost",
                        "--spring.data.redis.port=" + REDIS_PORT,
                        // Generous limits: this test is about the store being
                        // gone, not about a limit being reached.
                        "--ratelimiter.rules.endpoints.[" + FAIL_OPEN_PATH + "].algorithm=tb",
                        "--ratelimiter.rules.endpoints.[" + FAIL_OPEN_PATH + "].capacity=100000",
                        "--ratelimiter.rules.endpoints.[" + FAIL_OPEN_PATH + "].refill-rate=100000",
                        "--ratelimiter.rules.endpoints.[" + FAIL_OPEN_PATH + "].failure-mode=fail-open",
                        "--ratelimiter.rules.endpoints.[" + FAIL_CLOSED_PATH + "].algorithm=swl",
                        "--ratelimiter.rules.endpoints.[" + FAIL_CLOSED_PATH + "].limit=100000",
                        "--ratelimiter.rules.endpoints.[" + FAIL_CLOSED_PATH + "].window-ms=60000",
                        "--ratelimiter.rules.endpoints.[" + FAIL_CLOSED_PATH + "].failure-mode=fail-closed",
                        "--ratelimiter.breaker.failure-threshold=2",
                        "--ratelimiter.breaker.open-duration=500ms");
        port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @AfterAll
    static void stopEverything() {
        if (app != null) {
            app.close();
        }
        if (redis != null && redis.isRunning()) {
            redis.stop();
        }
    }

    @Test
    void redisOutage_degradesPerRuleAndNeverReturns500() throws Exception {
        // Healthy: both endpoints enforce normally and claim no degradation.
        assertEquals(200, get(FAIL_OPEN_PATH).statusCode());
        assertEquals(200, get(FAIL_CLOSED_PATH).statusCode());
        assertTrue(header(get(FAIL_OPEN_PATH), "X-RateLimit-Degraded").isEmpty());

        redis.stop();
        log.warn("redis stopped: every check from here on is made without the shared counter");

        // Thirty of each, so the breaker opens partway through and both the
        // timeout path and the short-circuit path are exercised.
        for (int i = 0; i < 30; i++) {
            HttpResponse<String> open = get(FAIL_OPEN_PATH);
            assertNotEquals(500, open.statusCode(), "a Redis outage must never surface as a server error");
            assertEquals(200, open.statusCode(), "fail-open must keep serving while Redis is gone");
            assertEquals("fail-open", header(open, "X-RateLimit-Degraded").orElse(null),
                    "a degraded decision must be visible on the wire, not silent");

            HttpResponse<String> closed = get(FAIL_CLOSED_PATH);
            assertNotEquals(500, closed.statusCode(), "a Redis outage must never surface as a server error");
            assertEquals(503, closed.statusCode(),
                    "fail-closed rejects with 503; a 429 would claim the caller exceeded a limit");
            assertEquals("fail-closed", header(closed, "X-RateLimit-Degraded").orElse(null));
            assertEquals("1", header(closed, "Retry-After").orElse(null));
        }

        redis = startRedis();
        log.warn("redis restarted: waiting for the breaker to probe and close");

        assertTrue(recovers(), "enforcement must resume on its own once Redis is reachable again");
    }

    /** The breaker closes on a successful probe, so recovery needs no restart and no operator. */
    private static boolean recovers() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() - deadline < 0) {
            HttpResponse<String> response = get(FAIL_CLOSED_PATH);
            if (response.statusCode() == 200 && header(response, "X-RateLimit-Degraded").isEmpty()) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        container.setPortBindings(List.of(REDIS_PORT + ":6379"));
        container.start();
        return container;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<String> header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("could not reserve a port for redis", e);
        }
    }
}
