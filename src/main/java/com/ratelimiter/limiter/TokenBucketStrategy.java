package com.ratelimiter.limiter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token bucket in Redis, mutated by one atomic Lua script.
 *
 * <p>The former {@code RedisTokenBucketStore}, moved behind
 * {@link RateLimiterStrategy}. Algorithm and atomicity are unchanged, which is
 * why {@code DistributedRaceTest} still passes untouched.
 *
 * <p>The script bean moved here from {@code RedisConfig}: a strategy owns its
 * state representation, so it owns the code that manipulates it.
 * {@link DefaultRedisScript} still caches the SHA1, so Spring invokes it with
 * EVALSHA and only falls back to EVAL on NOSCRIPT.
 */
@Component
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
                               @Value("${ratelimiter.capacity:10}") int capacity,
                               @Value("${ratelimiter.refill-rate:5.0}") double refillRate) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillRate = refillRate;

        DefaultRedisScript<List> tokenBucket = new DefaultRedisScript<>();
        tokenBucket.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        tokenBucket.setResultType(List.class); // {allowed, retryAfterMillis, remaining}
        this.script = tokenBucket;
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

        if (reply == null || reply.size() < 3) {
            throw new IllegalStateException(
                    "token_bucket.lua returned an unexpected reply for " + key + ": " + reply);
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
