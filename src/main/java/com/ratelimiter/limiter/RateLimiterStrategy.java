package com.ratelimiter.limiter;

/**
 * One rate-limiting algorithm: given a key, atomically decide whether this
 * request is allowed and say when to retry.
 *
 * <p>Deliberately mentions no Redis, no Lua, and no algorithm config. What varies
 * between the three algorithms is the state representation (hash / ZSET / two
 * counters), not the Java call shape — so none of that belongs here. An in-memory
 * implementation must be able to satisfy this interface.
 *
 * <p>{@code weight} is the exception that does belong: cost-per-request is a
 * property of the request, not of the algorithm.
 */
public interface RateLimiterStrategy {

    /** Key-namespace tag: {@code tb}, {@code swl}, {@code swc}. */
    String algorithmTag();

    /**
     * Decides and records the effect in one indivisible step. A check-then-record
     * split across two calls is the exact race this project exists to close.
     */
    RateLimitResult tryAcquire(String key, int weight);

    default RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * Builds {@code ratelimit:{algo}:{scope}:{identifier}}. Concrete over the tag
     * so the layout is defined once and callers never assemble keys themselves.
     */
    default String keyFor(String scope, String identifier) {
        return "ratelimit:" + algorithmTag() + ":" + scope + ":" + identifier;
    }
}
