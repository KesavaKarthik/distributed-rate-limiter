package com.ratelimiter.limiter;

import com.ratelimiter.rules.LimitRule;
import com.ratelimiter.rules.RuleIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pays the limiter's one-off costs at startup instead of charging them to the
 * first real caller.
 *
 * <p>Found by benchmarking: the first request to touch Redis has to establish
 * the Lettuce connection AND ship the script body for its first EVAL, which
 * together exceed the 50ms command timeout. On a fail-closed rule that surfaced
 * as a spurious 503 on the very first request after every deploy — a real
 * defect, and one that only appears under a timeout tight enough to be useful.
 *
 * <p>Warming on a throwaway key means the connection is open and every script's
 * SHA1 is cached in Redis before traffic arrives, so the first caller pays an
 * EVALSHA like everyone else.
 *
 * <p>Failure here is logged and swallowed: an app that cannot reach Redis at
 * boot must still start, because every rule already has a failure mode that says
 * what to do about it.
 */
@Component
public class LimiterWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LimiterWarmup.class);

    /** Namespaced away from every real scope so it can never collide with a live counter. */
    private static final String WARMUP_IDENTIFIER = "__warmup__";

    private final RuleIndex identityRules;
    private final RuleIndex ipRules;

    public LimiterWarmup(@Qualifier("identityRuleIndex") RuleIndex identityRules,
                         @Qualifier("ipRuleIndex") RuleIndex ipRules) {
        this.identityRules = identityRules;
        this.ipRules = ipRules;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startNanos = System.nanoTime();

        // One rule per distinct algorithm: the SHA1 cache is per SCRIPT, so
        // warming every rule would repeat the same three EVALs N times over.
        Map<String, LimitRule> byScript = new LinkedHashMap<>();
        Stream.concat(identityRules.allRules().stream(), ipRules.allRules().stream())
                .forEach(rule -> byScript.putIfAbsent(rule.strategy().algorithmTag(), rule));

        boolean warmed = false;
        for (LimitRule rule : byScript.values()) {
            warmed |= warm(rule);
        }

        if (warmed) {
            log.info("limiter warm: redis connection open and {} script(s) cached in {}ms",
                    byScript.size(), (System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    private boolean warm(LimitRule rule) {
        try {
            rule.tryAcquire(WARMUP_IDENTIFIER);
            return true;
        } catch (RuntimeException e) {
            log.warn("limiter warm-up could not reach redis ({}); the first real request will pay "
                    + "the connect cost and each rule's failure mode applies until it recovers",
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
