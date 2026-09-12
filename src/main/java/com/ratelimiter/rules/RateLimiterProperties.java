package com.ratelimiter.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole {@code ratelimiter.*} tree.
 *
 * <p>Extends {@link RuleProperties} deliberately: the root IS the global default
 * rule. That is what keeps Phase 1's flat {@code ratelimiter.capacity} /
 * {@code refill-rate} / {@code limit} / {@code window-ms} property names working
 * unchanged — the distributed tests boot with those exact arguments, so a rename
 * would have quietly moved them onto defaults and broken the regression proof.
 *
 * <p>Map keys are endpoint paths and identifiers. A path containing a dot needs
 * bracket notation in yaml ({@code "[/api/v1.0/x]"}); slashes are fine bare.
 */
@ConfigurationProperties("ratelimiter")
public class RateLimiterProperties extends RuleProperties {

    /** Paths the limiter never touches. Used for the k6 baseline, and for health checks. */
    private List<String> exemptPaths = List.of();

    private Rules rules = new Rules();
    private Ip ip = new Ip();
    private Breaker breaker = new Breaker();

    public List<String> getExemptPaths() {
        return exemptPaths;
    }

    public void setExemptPaths(List<String> exemptPaths) {
        this.exemptPaths = exemptPaths;
    }

    public Rules getRules() {
        return rules;
    }

    public void setRules(Rules rules) {
        this.rules = rules;
    }

    public Ip getIp() {
        return ip;
    }

    public void setIp(Ip ip) {
        this.ip = ip;
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public void setBreaker(Breaker breaker) {
        this.breaker = breaker;
    }

    /**
     * The five override tables, one per rung of the specificity ladder above the
     * global default. Nesting the composite tiers as map-of-map is not stylistic:
     * it is what lets resolution look up {@code (key, endpoint)} without building
     * a composite string, so the hot path allocates nothing.
     */
    public static class Rules {

        private Map<String, RuleProperties> endpoints = new LinkedHashMap<>();
        private Map<String, RuleProperties> users = new LinkedHashMap<>();
        private Map<String, RuleProperties> apiKeys = new LinkedHashMap<>();
        private Map<String, Map<String, RuleProperties>> userEndpoints = new LinkedHashMap<>();
        private Map<String, Map<String, RuleProperties>> apiKeyEndpoints = new LinkedHashMap<>();

        public Map<String, RuleProperties> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(Map<String, RuleProperties> endpoints) {
            this.endpoints = endpoints;
        }

        public Map<String, RuleProperties> getUsers() {
            return users;
        }

        public void setUsers(Map<String, RuleProperties> users) {
            this.users = users;
        }

        public Map<String, RuleProperties> getApiKeys() {
            return apiKeys;
        }

        public void setApiKeys(Map<String, RuleProperties> apiKeys) {
            this.apiKeys = apiKeys;
        }

        public Map<String, Map<String, RuleProperties>> getUserEndpoints() {
            return userEndpoints;
        }

        public void setUserEndpoints(Map<String, Map<String, RuleProperties>> userEndpoints) {
            this.userEndpoints = userEndpoints;
        }

        public Map<String, Map<String, RuleProperties>> getApiKeyEndpoints() {
            return apiKeyEndpoints;
        }

        public void setApiKeyEndpoints(Map<String, Map<String, RuleProperties>> apiKeyEndpoints) {
            this.apiKeyEndpoints = apiKeyEndpoints;
        }
    }

    /**
     * The coarse pre-auth tier. Separate from {@link Rules} because it is resolved
     * by a different filter at a different point in the chain, against an
     * identifier (the remote address) that exists before authentication does.
     *
     * <p>Off by default: every request in the existing distributed tests comes
     * from one loopback address, so an enabled IP tier would throttle the very
     * concurrency those tests are built to create.
     */
    public static class Ip {

        private boolean enabled = false;
        private RuleProperties rule = new RuleProperties();
        private Map<String, RuleProperties> endpoints = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RuleProperties getRule() {
            return rule;
        }

        public void setRule(RuleProperties rule) {
            this.rule = rule;
        }

        public Map<String, RuleProperties> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(Map<String, RuleProperties> endpoints) {
            this.endpoints = endpoints;
        }
    }

    /** Stops a Redis outage from charging every request the full command timeout. */
    public static class Breaker {

        private boolean enabled = true;
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }
}
