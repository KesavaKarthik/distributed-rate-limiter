package com.ratelimiter.limiter;

import java.util.List;

/**
 * Turns the {allowed, retryAfterMillis, remaining} array every limiter script
 * returns into a {@link RateLimitResult}.
 *
 * <p>Shared so the return contract is enforced in one place rather than
 * re-implemented (and allowed to drift) in each strategy.
 */
final class ScriptReply {

    private ScriptReply() {
    }

    static RateLimitResult toResult(List<?> reply, String script, String key) {
        if (reply == null || reply.size() < 3) {
            throw new IllegalStateException(
                    script + " returned an unexpected reply for " + key + ": " + reply);
        }

        long allowed = asLong(reply.get(0));
        long retryAfterMillis = asLong(reply.get(1));
        long remaining = asLong(reply.get(2));

        return allowed == 1L
                ? RateLimitResult.allow(remaining)
                : RateLimitResult.deny(retryAfterMillis, remaining);
    }

    /** Tolerant of the client surfacing a reply as a number or a string. */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
