package com.ratelimiter.resilience;

import com.ratelimiter.rules.RateLimiterProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The breaker is a counter, a deadline and a probe permit; each is asserted here. */
class RedisCircuitBreakerTest {

    private static RedisCircuitBreaker breaker(int threshold, Duration open) {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.getBreaker().setFailureThreshold(threshold);
        properties.getBreaker().setOpenDuration(open);
        return new RedisCircuitBreaker(properties);
    }

    @Test
    void staysClosedBelowTheThreshold() {
        RedisCircuitBreaker breaker = breaker(3, Duration.ofSeconds(5));

        breaker.recordFailure();
        breaker.recordFailure();

        assertTrue(breaker.allowAttempt());
        assertFalse(breaker.isOpen());
    }

    @Test
    void opensAtTheThresholdAndStopsPayingTheTimeout() {
        RedisCircuitBreaker breaker = breaker(3, Duration.ofSeconds(30));

        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }

        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowAttempt(), "an open breaker must not send another request to Redis");
        assertFalse(breaker.allowAttempt());
    }

    /** Failures are CONSECUTIVE: one success resets the count, so noise never accumulates into an outage. */
    @Test
    void oneSuccessResetsTheCount() {
        RedisCircuitBreaker breaker = breaker(3, Duration.ofSeconds(30));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertFalse(breaker.isOpen());
    }

    @Test
    void afterTheOpenWindow_exactlyOneRequestProbes() throws Exception {
        RedisCircuitBreaker breaker = breaker(1, Duration.ofMillis(50));

        breaker.recordFailure();
        assertFalse(breaker.allowAttempt());

        Thread.sleep(80);

        assertTrue(breaker.allowAttempt(), "one probe must be let through once the window expires");
        assertFalse(breaker.allowAttempt(), "the rest must keep short-circuiting while the probe is in flight");
    }

    @Test
    void aFailedProbeReopens_aSuccessfulOneCloses() throws Exception {
        RedisCircuitBreaker breaker = breaker(1, Duration.ofMillis(50));

        breaker.recordFailure();
        Thread.sleep(80);

        assertTrue(breaker.allowAttempt());
        breaker.recordFailure();
        assertFalse(breaker.allowAttempt(), "a failed probe must re-open, not close");

        Thread.sleep(80);
        assertTrue(breaker.allowAttempt());
        breaker.recordSuccess();

        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowAttempt());
    }

    @Test
    void disabledBreaker_alwaysAttempts() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.getBreaker().setEnabled(false);
        properties.getBreaker().setFailureThreshold(1);
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(properties);

        breaker.recordFailure();
        breaker.recordFailure();

        assertTrue(breaker.allowAttempt());
    }
}
