package com.ratelimiter.config;

import com.ratelimiter.limiter.RateLimiterStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Picks the active algorithm from {@code ratelimiter.algorithm}.
 *
 * <p>Every strategy stays a bean; this only marks one @Primary so the filter's
 * plain {@code RateLimiterStrategy} dependency resolves. Chosen over
 * {@code @ConditionalOnProperty}, which would scatter the selection rule across
 * the strategy classes and turn a typo into "no qualifying bean" with no mention
 * of the property. Keeping all three registered also lets a test or benchmark
 * address any algorithm without rebooting the context.
 *
 * <p>A new algorithm registers by being a @Component. Nothing here changes.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    @Primary
    public RateLimiterStrategy activeRateLimiterStrategy(
            List<RateLimiterStrategy> strategies,
            @Value("${ratelimiter.algorithm:tb}") String algorithm) {

        return strategies.stream()
                .filter(strategy -> strategy.algorithmTag().equalsIgnoreCase(algorithm))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown ratelimiter.algorithm '" + algorithm + "'. Registered: "
                                + strategies.stream().map(RateLimiterStrategy::algorithmTag).sorted().toList()));
    }
}
