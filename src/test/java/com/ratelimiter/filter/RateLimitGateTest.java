package com.ratelimiter.filter;

import com.ratelimiter.limiter.RateLimitResult;
import com.ratelimiter.limiter.RateLimiterStrategy;
import com.ratelimiter.resilience.DegradedLogger;
import com.ratelimiter.resilience.RedisCircuitBreaker;
import com.ratelimiter.rules.FailureMode;
import com.ratelimiter.rules.LimitRule;
import com.ratelimiter.rules.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract under test is negative: <b>no Redis failure escapes the gate.</b>
 * A limiter that 500s when its store is down is a worse outage than the one it
 * was added to prevent.
 *
 * <p>Needs no Redis, because {@link RateLimiterStrategy} mentions none — a stub
 * that throws what Spring's exception translation would throw is enough.
 */
class RateLimitGateTest {

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void allowed_passesThroughAndReportsRemaining() throws Exception {
        boolean allowed = gate().allow(response, rule(FailureMode.FAIL_OPEN, () -> RateLimitResult.allow(7)), "alice");

        assertTrue(allowed);
        assertEquals("7", response.getHeader("X-RateLimit-Remaining"));
        assertEquals("the-rule", response.getHeader("X-RateLimit-Rule"));
        assertNull(response.getHeader("X-RateLimit-Degraded"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void denied_is429WithRetryAfterInWholeSeconds() throws Exception {
        boolean allowed = gate().allow(response, rule(FailureMode.FAIL_OPEN, () -> RateLimitResult.deny(1200, 0)), "alice");

        assertFalse(allowed);
        assertEquals(429, response.getStatus());
        assertEquals("2", response.getHeader("Retry-After"), "1200ms must round up, never down to a busy retry");
        assertNull(response.getHeader("X-RateLimit-Degraded"));
    }

    @Test
    void deniedWithNoPossibleRetry_omitsRetryAfter() throws Exception {
        gate().allow(response, rule(FailureMode.FAIL_OPEN, () -> RateLimitResult.deny(-1, 0)), "alice");

        assertEquals(429, response.getStatus());
        assertNull(response.getHeader("Retry-After"), "a request that can never succeed must not promise a time");
    }

    @Test
    void redisTimeout_failsOpen_andSaysSo() throws Exception {
        boolean allowed = gate().allow(response,
                rule(FailureMode.FAIL_OPEN, () -> {
                    throw new QueryTimeoutException("redis took too long");
                }), "alice");

        assertTrue(allowed, "fail-open must serve the request");
        assertEquals(200, response.getStatus());
        assertEquals("fail-open", response.getHeader("X-RateLimit-Degraded"));
    }

    @Test
    void redisDown_failsClosed_with503NotA429() throws Exception {
        boolean allowed = gate().allow(response,
                rule(FailureMode.FAIL_CLOSED, () -> {
                    throw new RedisConnectionFailureException("no route to redis");
                }), "alice");

        assertFalse(allowed);
        assertEquals(503, response.getStatus(),
                "503 keeps 429 meaning 'you exceeded your limit' — degradation is not enforcement");
        assertEquals("1", response.getHeader("Retry-After"));
        assertEquals("fail-closed", response.getHeader("X-RateLimit-Degraded"));
    }

    /** A bug in the limiter is still not allowed to become a 500 on the caller's request. */
    @Test
    void anUnexpectedFault_degradesRatherThanPropagating() throws Exception {
        boolean allowed = gate().allow(response,
                rule(FailureMode.FAIL_OPEN, () -> {
                    throw new IllegalStateException("malformed script reply");
                }), "alice");

        assertTrue(allowed);
        assertEquals("fail-open", response.getHeader("X-RateLimit-Degraded"));
    }

    @Test
    void onceTheBreakerIsOpen_redisIsNotCalledAtAll() throws Exception {
        RedisCircuitBreaker breaker = breaker(1);
        RateLimitGate gate = new RateLimitGate(breaker, new DegradedLogger());
        int[] calls = {0};

        LimitRule failing = rule(FailureMode.FAIL_OPEN, () -> {
            calls[0]++;
            throw new QueryTimeoutException("redis took too long");
        });

        gate.allow(response, failing, "alice");                          // trips the breaker
        gate.allow(new MockHttpServletResponse(), failing, "alice");     // short-circuited
        gate.allow(new MockHttpServletResponse(), failing, "alice");

        assertEquals(1, calls[0], "an open breaker must not keep paying the command timeout");
    }

    private static RateLimitGate gate() {
        return new RateLimitGate(breaker(1000), new DegradedLogger());
    }

    private static RedisCircuitBreaker breaker(int threshold) {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.getBreaker().setFailureThreshold(threshold);
        properties.getBreaker().setOpenDuration(Duration.ofSeconds(30));
        return new RedisCircuitBreaker(properties);
    }

    private static LimitRule rule(FailureMode mode, Supplier<RateLimitResult> outcome) {
        RateLimiterStrategy strategy = new RateLimiterStrategy() {
            @Override
            public String algorithmTag() {
                return "stub";
            }

            @Override
            public RateLimitResult tryAcquire(String key, int weight) {
                return outcome.get();
            }
        };
        return new LimitRule("the-rule", "ratelimit:stub:test:", strategy, mode);
    }
}
