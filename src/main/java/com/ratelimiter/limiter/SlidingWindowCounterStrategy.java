package com.ratelimiter.limiter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sliding window counter: the log's rolling bound approximated at O(1) memory,
 * from the current window's count plus the previous window's count decayed by
 * how far into the current window we are.
 *
 * <p>Takes the same {@code limit} / {@code window-ms} pair as the log on purpose
 * — they enforce the same guarantee at different cost, so sharing the config
 * makes them directly comparable under one load test.
 */
@Component
public class SlidingWindowCounterStrategy implements RateLimiterStrategy {

    private final StringRedisTemplate redis;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    private final int limit;
    private final long windowMs;

    @SuppressWarnings("rawtypes")
    public SlidingWindowCounterStrategy(StringRedisTemplate redis,
                                        @Value("${ratelimiter.limit:100}") int limit,
                                        @Value("${ratelimiter.window-ms:60000}") long windowMs) {
        this.redis = redis;
        this.limit = limit;
        this.windowMs = windowMs;

        DefaultRedisScript<List> slidingWindowCounter = new DefaultRedisScript<>();
        slidingWindowCounter.setLocation(new ClassPathResource("scripts/sliding_window_counter.lua"));
        slidingWindowCounter.setResultType(List.class);
        this.script = slidingWindowCounter;
    }

    @Override
    public String algorithmTag() {
        return "swc";
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
                Integer.toString(weight));

        return ScriptReply.toResult(reply, "sliding_window_counter.lua", key);
    }
}
