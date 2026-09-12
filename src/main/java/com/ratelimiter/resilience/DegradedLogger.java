package com.ratelimiter.resilience;

import com.ratelimiter.rules.FailureMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs degraded decisions without turning a Redis outage into a log outage.
 *
 * <p>Every degraded request is a decision made without the shared counter, so it
 * matters and must be visible. But at the rate a limiter runs, logging each one
 * would flood the appender exactly when the system is already unhealthy. So the
 * first one in each window logs, the rest are counted and reported by the next.
 */
@Component
public class DegradedLogger {

    private static final Logger log = LoggerFactory.getLogger(DegradedLogger.class);

    private static final long WINDOW_NANOS = 5_000_000_000L;

    // Seeded from the clock rather than 0: nanoTime has an arbitrary origin and
    // may be negative, so a fixed sentinel cannot be compared against it safely.
    private final AtomicLong nextLogAtNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong suppressed = new AtomicLong();

    public void degraded(String ruleId, FailureMode mode, String reason, Throwable cause) {
        long now = System.nanoTime();
        long next = nextLogAtNanos.get();

        if (now - next < 0 || !nextLogAtNanos.compareAndSet(next, now + WINDOW_NANOS)) {
            suppressed.incrementAndGet();
            return;
        }

        long alsoSuppressed = suppressed.getAndSet(0L);
        String verb = mode.isOpen() ? "ALLOWED without a limit check" : "REJECTED with 503";

        if (cause != null) {
            log.warn("rate limit degraded [{}]: {} — request {} ({} similar in the last {}s)",
                    ruleId, reason, verb, alsoSuppressed, WINDOW_NANOS / 1_000_000_000L, cause);
        } else {
            log.warn("rate limit degraded [{}]: {} — request {} ({} similar in the last {}s)",
                    ruleId, reason, verb, alsoSuppressed, WINDOW_NANOS / 1_000_000_000L);
        }
    }
}
