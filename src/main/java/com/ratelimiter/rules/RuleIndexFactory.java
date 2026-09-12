package com.ratelimiter.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the resolution tables at startup: validate every rule, instantiate its
 * strategy, index it under its tier.
 *
 * <p>All of the work happens here so the request path does none of it. A rule
 * that does not validate throws from a {@code @Bean} method, which aborts context
 * refresh — the application refuses to start rather than serving traffic under a
 * limit nobody intended.
 *
 * <p>Scope segments keep the tiers from colliding in the keyspace. The global
 * default keeps the literal scope {@code user}, so its keys stay
 * {@code ratelimit:tb:user:{id}} exactly as in Phase 1.
 */
@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RuleIndexFactory {

    private static final Logger log = LoggerFactory.getLogger(RuleIndexFactory.class);

    private static final String SCOPE_DEFAULT = "user";
    private static final String SCOPE_USER = "u";
    private static final String SCOPE_API_KEY = "k";
    private static final String SCOPE_ENDPOINT = "ep|";
    private static final String SCOPE_USER_ENDPOINT = "ue|";
    private static final String SCOPE_API_KEY_ENDPOINT = "ke|";
    private static final String SCOPE_IP = "ip";
    private static final String SCOPE_IP_ENDPOINT = "ipe|";

    /** The fine-grained ladder, resolved after authentication. */
    @Bean
    public RuleIndex identityRuleIndex(RateLimiterProperties properties, StrategyFactory factory) {
        FailureMode defaultMode = failureMode(properties);

        // Lenient on unused fields: the flat ratelimiter.* keys carry config for
        // all three algorithms so the active one is a one-property switch.
        LimitRule defaultRule = factory.build("default", SCOPE_DEFAULT, properties, defaultMode, false);

        RateLimiterProperties.Rules rules = properties.getRules();

        RuleIndex index = new RuleIndex(
                nested(rules.getApiKeyEndpoints(), "api-key+endpoint", SCOPE_API_KEY_ENDPOINT, factory, defaultMode),
                nested(rules.getUserEndpoints(), "user+endpoint", SCOPE_USER_ENDPOINT, factory, defaultMode),
                endpointTable(rules.getEndpoints(), "endpoint", SCOPE_ENDPOINT, factory, defaultMode),
                flat(rules.getApiKeys(), "api-key", SCOPE_API_KEY, factory, defaultMode),
                flat(rules.getUsers(), "user", SCOPE_USER, factory, defaultMode),
                defaultRule);

        log.info("rate limit rules: default={}, endpoint={}, user={}, api-key={}, user+endpoint={}, api-key+endpoint={}",
                defaultRule.strategy().algorithmTag(),
                rules.getEndpoints().size(), rules.getUsers().size(), rules.getApiKeys().size(),
                countNested(rules.getUserEndpoints()), countNested(rules.getApiKeyEndpoints()));

        return index;
    }

    /**
     * The coarse ladder, resolved before authentication where the remote address
     * is the only identity available. Modelled as a two-rung {@link RuleIndex}
     * (endpoint, then default) rather than its own type — the resolution logic is
     * identical, only the populated tiers differ.
     *
     * <p>Built and validated even when the tier is disabled, so turning it on
     * cannot be the moment a config error is discovered. An unconfigured
     * {@code ip.rule} falls back to the global default rule's numbers, applied
     * per address.
     */
    @Bean
    public RuleIndex ipRuleIndex(RateLimiterProperties properties, StrategyFactory factory) {
        FailureMode defaultMode = failureMode(properties);
        RateLimiterProperties.Ip ip = properties.getIp();

        boolean configured = ip.getRule().getAlgorithm() != null;
        RuleProperties source = configured ? ip.getRule() : properties;
        LimitRule ipDefault = factory.build("ip-default", SCOPE_IP, source, defaultMode, configured);

        return new RuleIndex(Map.of(), Map.of(),
                endpointTable(ip.getEndpoints(), "ip+endpoint", SCOPE_IP_ENDPOINT, factory, defaultMode),
                Map.of(), Map.of(), ipDefault);
    }

    /** The limiter must not be a bigger outage than the service it protects. */
    private static FailureMode failureMode(RateLimiterProperties properties) {
        return properties.getFailureMode() != null ? properties.getFailureMode() : FailureMode.FAIL_OPEN;
    }

    private static Map<String, LimitRule> flat(Map<String, RuleProperties> source,
                                               String tier,
                                               String scope,
                                               StrategyFactory factory,
                                               FailureMode defaultMode) {
        Map<String, LimitRule> table = new LinkedHashMap<>();
        source.forEach((identifier, rule) -> {
            String id = tier + "[" + identifier + "]";
            requireIdentifier(identifier, id);
            table.put(identifier, factory.build(id, scope, rule, defaultMode, true));
        });
        return Map.copyOf(table);
    }

    private static Map<String, LimitRule> endpointTable(Map<String, RuleProperties> source,
                                                        String tier,
                                                        String scopePrefix,
                                                        StrategyFactory factory,
                                                        FailureMode defaultMode) {
        Map<String, LimitRule> table = new LinkedHashMap<>();
        source.forEach((path, rule) -> {
            String id = tier + "[" + path + "]";
            requirePath(path, id);
            table.put(path, factory.build(id, scopePrefix + path, rule, defaultMode, true));
        });
        return Map.copyOf(table);
    }

    private static Map<String, Map<String, LimitRule>> nested(Map<String, Map<String, RuleProperties>> source,
                                                              String tier,
                                                              String scopePrefix,
                                                              StrategyFactory factory,
                                                              FailureMode defaultMode) {
        Map<String, Map<String, LimitRule>> table = new LinkedHashMap<>();
        source.forEach((identifier, byPath) -> {
            Map<String, LimitRule> inner = new LinkedHashMap<>();
            byPath.forEach((path, rule) -> {
                String id = tier + "[" + identifier + " " + path + "]";
                requireIdentifier(identifier, id);
                requirePath(path, id);
                // The identifier is already the key's last segment, so only the
                // path needs to go in the scope to keep this tier distinct.
                inner.put(path, factory.build(id, scopePrefix + path, rule, defaultMode, true));
            });
            table.put(identifier, Map.copyOf(inner));
        });
        return Map.copyOf(table);
    }

    /** Exact match only, so a path that cannot be a request URI can never fire. */
    private static void requirePath(String path, String ruleId) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalStateException("Invalid rate limit rule " + ruleId
                    + ": endpoint must be an absolute path starting with a slash, was " + path);
        }
    }

    private static void requireIdentifier(String identifier, String ruleId) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalStateException("Invalid rate limit rule " + ruleId + ": identifier must not be blank");
        }
    }

    private static int countNested(Map<String, Map<String, RuleProperties>> source) {
        return source.values().stream().mapToInt(Map::size).sum();
    }
}
