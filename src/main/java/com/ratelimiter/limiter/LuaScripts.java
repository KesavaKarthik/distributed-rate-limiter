package com.ratelimiter.limiter;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The three limiter scripts, loaded once.
 *
 * <p>Lifted out of the strategy constructors in Phase 2 because there is now one
 * strategy instance <i>per rule</i>: leaving the load there would have meant a
 * fresh {@link DefaultRedisScript} — and so a fresh SHA1 cache and a repeated
 * SCRIPT LOAD — for every rule in the config. A strategy still owns the logic
 * that manipulates its own state; only the loaded script object is shared.
 *
 * <p>Each script returns {@code {allowed, retryAfterMillis, remaining}}, which is
 * why they all declare {@code List} as the result type.
 */
@Component
public class LuaScripts {

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> tokenBucket = load("scripts/token_bucket.lua");

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> slidingWindowLog = load("scripts/sliding_window_log.lua");

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> slidingWindowCounter = load("scripts/sliding_window_counter.lua");

    @SuppressWarnings("rawtypes")
    public RedisScript<List> tokenBucket() {
        return tokenBucket;
    }

    @SuppressWarnings("rawtypes")
    public RedisScript<List> slidingWindowLog() {
        return slidingWindowLog;
    }

    @SuppressWarnings("rawtypes")
    public RedisScript<List> slidingWindowCounter() {
        return slidingWindowCounter;
    }

    /** Caches the SHA1, so Spring invokes with EVALSHA and only falls back to EVAL on NOSCRIPT. */
    @SuppressWarnings("rawtypes")
    private static RedisScript<List> load(String classpathLocation) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(List.class);
        return script;
    }
}
