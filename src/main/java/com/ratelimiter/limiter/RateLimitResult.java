package com.ratelimiter.limiter;

/**
 * One rate-limit decision.
 *
 * <p>All three fields are computed inside the Lua script, from the snapshot that
 * made the decision. Java cannot reconstruct them afterwards: a second lookup
 * would read a later state and would itself race. So every algorithm's script
 * must return this shape.
 *
 * @param retryAfterMillis 0 when allowed, {@link #RETRY_NEVER} when no retry can
 *                         ever succeed under the current config
 * @param remaining        further weight-1 requests admissible right now, floored
 */
public record RateLimitResult(boolean allowed, long retryAfterMillis, long remaining) {

    /** No bounded retry time exists — e.g. a token bucket with refill rate 0. */
    public static final long RETRY_NEVER = -1L;

    public RateLimitResult {
        // A script may compute a negative (an overshooting estimate); "less than
        // none" is not something a caller can act on.
        if (remaining < 0) {
            remaining = 0;
        }
    }

    public static RateLimitResult allow(long remaining) {
        return new RateLimitResult(true, 0L, remaining);
    }

    public static RateLimitResult deny(long retryAfterMillis, long remaining) {
        return new RateLimitResult(false, retryAfterMillis, remaining);
    }

    public boolean hasRetryTime() {
        return retryAfterMillis > 0;
    }
}
