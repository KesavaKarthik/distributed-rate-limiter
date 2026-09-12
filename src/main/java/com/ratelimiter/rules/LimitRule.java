package com.ratelimiter.rules;

import com.ratelimiter.limiter.RateLimitResult;
import com.ratelimiter.limiter.RateLimiterStrategy;

/**
 * A validated rule: which algorithm, configured with which numbers, and what to
 * do when Redis cannot answer.
 *
 * <p>Built once at startup and immutable, so resolution hands the filter a rule
 * it can use immediately instead of constructing anything per request.
 *
 * <p>The {@code strategy} is an instance dedicated to this rule, with this rule's
 * capacity/limit already bound into it. That is why per-rule configuration did
 * not need a spec parameter on {@link RateLimiterStrategy#tryAcquire}: rules are
 * static config, so the numbers can still be bound at construction and the
 * interface stays free of algorithm vocabulary.
 */
public record LimitRule(String id, String keyPrefix, RateLimiterStrategy strategy, FailureMode failureMode) {

    /**
     * {@code keyPrefix} is {@code ratelimit:{algo}:{scope}:} — everything but the
     * caller — so the only string built per request is this concatenation.
     */
    public String keyFor(String principal) {
        return keyPrefix + principal;
    }

    public RateLimitResult tryAcquire(String principal) {
        return strategy.tryAcquire(keyFor(principal));
    }
}
