package com.ratelimiter.rules;

/**
 * What the limiter does when it cannot reach Redis.
 *
 * <p>Per rule, because the right answer is not global: an outage that makes a
 * read endpoint unlimited is survivable, while one that makes an expensive
 * endpoint unlimited is worse than making it unavailable.
 */
public enum FailureMode {

    /** Allow the request. Favours availability of the protected service. */
    FAIL_OPEN,

    /** Reject the request. Favours protection of the resource behind the limit. */
    FAIL_CLOSED;

    /** Boot's relaxed binding maps {@code fail-open} to {@code FAIL_OPEN}, so yaml stays kebab-case. */
    public boolean isOpen() {
        return this == FAIL_OPEN;
    }
}
