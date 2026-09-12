package com.ratelimiter.limiter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Token bucket in Redis, mutated by one atomic Lua script.
 *
 * <p>The former {@code RedisTokenBucketStore}, moved behind
 * {@link RateLimiterStrategy}. Algorithm and atomicity are unchanged, which is
 * why {@code DistributedRaceTest} still passes untouched.
 *
 * <p>Phase 2 makes this one instance per rule rather than one bean: the capacity
 * and refill rate a rule configures are still bound at construction, so
 * {@link RateLimiterStrategy} never had to grow a config parameter. The loaded
 * script comes from {@link LuaScripts} so N rules do not mean N SCRIPT LOADs.
 */
public class TokenBucketStrategy implements RateLimiterStrategy {

    private final StringRedisTemplate redis;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    // Bound at construction, not passed per call: capacity and refill rate are
    // token-bucket vocabulary and would leak through the interface.
    private final int capacity;
    private final double refillRate;

    @SuppressWarnings("rawtypes")
    public TokenBucketStrategy(StringRedisTemplate redis,
                               RedisScript<List> script,
                               int capacity,
                               double refillRate) {
        this.redis = redis;
        this.script = script; // {allowed, retryAfterMillis, remaining}
        this.capacity = capacity;
        this.refillRate = refillRate;
    }

    @Override
    public String algorithmTag() {
        return "tb";
    }

    /** No timestamp is passed in — the script reads Redis's clock, so all instances share one. */
    @Override
    public RateLimitResult tryAcquire(String key, int weight) {
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1, was " + weight);
        }

        // Key goes via KEYS so Redis Cluster can route it; config via ARGV.
        List<?> reply = redis.execute(
                script,
                List.of(key),
                Integer.toString(capacity),
                Double.toString(refillRate),
                Integer.toString(weight));

        return ScriptReply.toResult(reply, "token_bucket.lua", key);
    }
}
