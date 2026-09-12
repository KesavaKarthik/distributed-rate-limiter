package com.ratelimiter.resilience;

import com.ratelimiter.rules.RateLimiterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stops a Redis outage from charging every request the full command timeout.
 *
 * <p>The command timeout alone already prevents a hung call from holding a Tomcat
 * thread forever, but on its own it means each request during an outage waits the
 * timeout before being allowed or denied — the limiter becomes the latency
 * problem it was meant to avoid. Once enough consecutive calls have failed, the
 * outcome is already known, so this short-circuits to the rule's failure mode at
 * no cost and pays the timeout again only on a single periodic probe.
 *
 * <p>Deliberately not Resilience4j: the whole state machine is a counter, a
 * deadline, and a probe permit, and a dependency would obscure that.
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);

    private final boolean enabled;
    private final int failureThreshold;
    private final long openDurationNanos;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** Zero means closed. Otherwise the nanoTime deadline after which one probe is allowed. */
    private final AtomicLong openUntilNanos = new AtomicLong();

    /** Ensures exactly one request probes a possibly-recovered Redis, not all of them. */
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    public RedisCircuitBreaker(RateLimiterProperties properties) {
        RateLimiterProperties.Breaker breaker = properties.getBreaker();
        this.enabled = breaker.isEnabled();
        this.failureThreshold = breaker.getFailureThreshold();
        this.openDurationNanos = breaker.getOpenDuration().toNanos();
    }

    /** False means skip Redis entirely and go straight to the rule's failure mode. */
    public boolean allowAttempt() {
        if (!enabled) {
            return true;
        }
        long openUntil = openUntilNanos.get();
        if (openUntil == 0L) {
            return true;
        }
        // Subtraction, not comparison: nanoTime can wrap.
        if (System.nanoTime() - openUntil >= 0) {
            return probeInFlight.compareAndSet(false, true);
        }
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        if (openUntilNanos.getAndSet(0L) != 0L) {
            probeInFlight.set(false);
            log.info("redis reachable again; rate limiting is enforced normally");
        }
    }

    public void recordFailure() {
        probeInFlight.set(false); // a failed probe releases the permit for the next window
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            long until = System.nanoTime() + openDurationNanos;
            if (openUntilNanos.getAndSet(until) == 0L) {
                log.warn("redis unreachable after {} consecutive failures; short-circuiting to each rule's "
                        + "failure mode for {}ms", failures, openDurationNanos / 1_000_000L);
            }
        }
    }

    public boolean isOpen() {
        return openUntilNanos.get() != 0L;
    }
}
