package com.ratelimiter.limiter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sliding window log: a hard rolling ceiling of {@code limit} requests in any
 * trailing window of {@code windowMs}, backed by a ZSET of admitted timestamps.
 *
 * <p>Exact, with an exact retry-after, at O(limit) memory per key — only
 * admitted requests are logged, so the ZSET size is bounded by the limit and not
 * by how hard a client hammers it.
 */
@Component
public class SlidingWindowLogStrategy implements RateLimiterStrategy {

    private final StringRedisTemplate redis;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    private final int limit;
    private final long windowMs;

    @SuppressWarnings("rawtypes")
    public SlidingWindowLogStrategy(StringRedisTemplate redis,
                                    @Value("${ratelimiter.limit:100}") int limit,
                                    @Value("${ratelimiter.window-ms:60000}") long windowMs) {
        this.redis = redis;
        this.limit = limit;
        this.windowMs = windowMs;

        DefaultRedisScript<List> slidingWindowLog = new DefaultRedisScript<>();
        slidingWindowLog.setLocation(new ClassPathResource("scripts/sliding_window_log.lua"));
        slidingWindowLog.setResultType(List.class);
        this.script = slidingWindowLog;
    }

    @Override
    public String algorithmTag() {
        return "swl";
    }

    @Override
    public RateLimitResult tryAcquire(String key, int weight) {
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1, was " + weight);
        }

        List<?> reply = redis.execute(
                script,
                List.of(key),
                Integer.toString(limit),
                Long.toString(windowMs),
                Integer.toString(weight),
                nonce());

        return ScriptReply.toResult(reply, "sliding_window_log.lua", key);
    }

    /**
     * Makes the ZSET member unique so two requests in the same millisecond cannot
     * collapse into one entry. Generated here because Redis seeds Lua's PRNG
     * deterministically per script run; it is a nonce, not a clock, so it does
     * not reintroduce app-side time.
     */
    private static String nonce() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }
}
