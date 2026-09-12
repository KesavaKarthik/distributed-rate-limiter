package com.ratelimiter.rules;

import com.ratelimiter.limiter.LuaScripts;
import com.ratelimiter.limiter.RateLimiterStrategy;
import com.ratelimiter.limiter.SlidingWindowCounterStrategy;
import com.ratelimiter.limiter.SlidingWindowLogStrategy;
import com.ratelimiter.limiter.TokenBucketStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates one rule and turns it into a {@link LimitRule} with its own strategy
 * instance.
 *
 * <p>Validation runs at startup and throws, so a malformed rule aborts context
 * refresh instead of surfacing as a wrong limit in production. It replaces the
 * unknown-algorithm check that used to live in {@code RateLimiterConfig}, and
 * keeps that check's habit of naming the registered tags in the message.
 */
@Component
public class StrategyFactory {

    static final String TOKEN_BUCKET = "tb";
    static final String SLIDING_WINDOW_LOG = "swl";
    static final String SLIDING_WINDOW_COUNTER = "swc";

    private static final List<String> TAGS =
            List.of(TOKEN_BUCKET, SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER);

    private final StringRedisTemplate redis;
    private final LuaScripts scripts;

    public StrategyFactory(StringRedisTemplate redis, LuaScripts scripts) {
        this.redis = redis;
        this.scripts = scripts;
    }

    /**
     * @param ruleId       human-readable, quoted back in errors and degraded logs
     * @param scope        the key's scope segment, fixed for this rule
     * @param defaultMode  used when the rule states no failure mode of its own
     * @param strictFields rejects fields the algorithm cannot use. False only for
     *                     the global default rule, whose flat keys deliberately
     *                     carry config for all three algorithms at once so the
     *                     active one can be switched with a single property.
     */
    public LimitRule build(String ruleId,
                           String scope,
                           RuleProperties rule,
                           FailureMode defaultMode,
                           boolean strictFields) {

        String algorithm = require(rule.getAlgorithm(), ruleId, "algorithm",
                "every rule states its own algorithm — an override replaces a rule, it never inherits half of one");

        RateLimiterStrategy strategy = switch (algorithm.toLowerCase()) {
            case TOKEN_BUCKET -> tokenBucket(ruleId, rule, strictFields);
            case SLIDING_WINDOW_LOG -> {
                Window w = window(ruleId, rule, strictFields);
                yield new SlidingWindowLogStrategy(redis, scripts.slidingWindowLog(), w.limit(), w.windowMs());
            }
            case SLIDING_WINDOW_COUNTER -> {
                Window w = window(ruleId, rule, strictFields);
                yield new SlidingWindowCounterStrategy(redis, scripts.slidingWindowCounter(), w.limit(), w.windowMs());
            }
            default -> throw invalid(ruleId, "unknown algorithm '" + algorithm + "'; registered: " + TAGS);
        };

        FailureMode mode = rule.getFailureMode() != null ? rule.getFailureMode() : defaultMode;

        // Built once here so the request path concatenates the caller onto a
        // finished prefix. keyFor() still owns the layout, so it is defined once.
        String keyPrefix = strategy.keyFor(scope, "");

        return new LimitRule(ruleId, keyPrefix, strategy, mode);
    }

    private TokenBucketStrategy tokenBucket(String ruleId, RuleProperties rule, boolean strictFields) {
        int capacity = require(rule.getCapacity(), ruleId, "capacity", "token bucket needs a burst size");
        double refillRate = require(rule.getRefillRate(), ruleId, "refill-rate", "token bucket needs a sustained rate");

        if (capacity < 1) {
            throw invalid(ruleId, "capacity must be >= 1, was " + capacity);
        }
        if (refillRate < 0) {
            throw invalid(ruleId, "refill-rate must be >= 0, was " + refillRate);
        }
        if (strictFields) {
            rejectUnused(ruleId, TOKEN_BUCKET, "limit", rule.getLimit());
            rejectUnused(ruleId, TOKEN_BUCKET, "window-ms", rule.getWindowMs());
        }
        return new TokenBucketStrategy(redis, scripts.tokenBucket(), capacity, refillRate);
    }

    private Window window(String ruleId, RuleProperties rule, boolean strictFields) {
        int limit = require(rule.getLimit(), ruleId, "limit", "a sliding window needs a request ceiling");
        long windowMs = require(rule.getWindowMs(), ruleId, "window-ms", "a sliding window needs a width");

        if (limit < 1) {
            throw invalid(ruleId, "limit must be >= 1, was " + limit);
        }
        if (windowMs < 1) {
            throw invalid(ruleId, "window-ms must be >= 1, was " + windowMs);
        }
        if (strictFields) {
            rejectUnused(ruleId, "sliding window", "capacity", rule.getCapacity());
            rejectUnused(ruleId, "sliding window", "refill-rate", rule.getRefillRate());
        }
        return new Window(limit, windowMs);
    }

    private record Window(int limit, long windowMs) {
    }

    private static <T> T require(T value, String ruleId, String field, String why) {
        if (value == null) {
            throw invalid(ruleId, "missing '" + field + "' (" + why + ")");
        }
        return value;
    }

    /** A set-but-ignored field is nearly always a typo'd algorithm, so it fails rather than being dropped. */
    private static void rejectUnused(String ruleId, String algorithm, String field, Object value) {
        if (value != null) {
            throw invalid(ruleId, "'" + field + "' is set but " + algorithm
                    + " does not use it — check the algorithm is the one you meant");
        }
    }

    private static IllegalStateException invalid(String ruleId, String problem) {
        return new IllegalStateException("Invalid rate limit rule " + ruleId + ": " + problem);
    }
}
