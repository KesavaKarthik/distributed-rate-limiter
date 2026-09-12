package com.ratelimiter.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Resolves a request to exactly one {@link LimitRule}, in memory.
 *
 * <p><b>First match on specificity, never a merge.</b> The ladder is
 * key+endpoint, user+endpoint, endpoint, key, user, global default; the first
 * table that has an entry wins outright. A more specific rule <i>replaces</i> a
 * less specific one — it does not tighten it — so a per-user rule of 1000/s beats
 * a per-endpoint rule of 10/s. That is the deliberate cost of the policy: it is
 * the only one a caller can predict without reading the whole table, and it is
 * the only one with a defined answer when two matching rules name different
 * algorithms.
 *
 * <p>No Redis call. Rules are configuration — identical on every instance,
 * uncontended, changing at deploy rather than per request — so fetching them
 * remotely would add a second round trip to the request path for data that never
 * varies. The whole point of Phase 1 was that one RTT is the price of
 * correctness; a second one for config would be pure waste.
 *
 * <p>The composite tiers are map-of-map rather than a map keyed on
 * {@code key + "\0" + endpoint} so a lookup is two hash probes on strings that
 * already exist. The composite-string form would allocate on every request.
 */
public final class RuleIndex {

    private final Map<String, Map<String, LimitRule>> byApiKeyAndEndpoint;
    private final Map<String, Map<String, LimitRule>> byUserAndEndpoint;
    private final Map<String, LimitRule> byEndpoint;
    private final Map<String, LimitRule> byApiKey;
    private final Map<String, LimitRule> byUser;
    private final LimitRule defaultRule;

    /** Flat view of everything indexed here, for startup warm-up and logging. */
    private final List<LimitRule> allRules;

    RuleIndex(Map<String, Map<String, LimitRule>> byApiKeyAndEndpoint,
              Map<String, Map<String, LimitRule>> byUserAndEndpoint,
              Map<String, LimitRule> byEndpoint,
              Map<String, LimitRule> byApiKey,
              Map<String, LimitRule> byUser,
              LimitRule defaultRule) {
        this.byApiKeyAndEndpoint = byApiKeyAndEndpoint;
        this.byUserAndEndpoint = byUserAndEndpoint;
        this.byEndpoint = byEndpoint;
        this.byApiKey = byApiKey;
        this.byUser = byUser;
        this.defaultRule = defaultRule;

        List<LimitRule> all = new ArrayList<>();
        all.add(defaultRule);
        byEndpoint.values().forEach(all::add);
        byApiKey.values().forEach(all::add);
        byUser.values().forEach(all::add);
        byApiKeyAndEndpoint.values().forEach(inner -> all.addAll(inner.values()));
        byUserAndEndpoint.values().forEach(inner -> all.addAll(inner.values()));
        this.allRules = List.copyOf(all);
    }

    public Collection<LimitRule> allRules() {
        return allRules;
    }

    /**
     * Never returns null: the global default is the floor of the ladder, which is
     * what guarantees every request resolves to something.
     *
     * @param apiKey   may be null when the caller presented no key
     * @param userId   may be null when the caller is unauthenticated
     * @param endpoint the exact request path
     */
    public LimitRule resolve(String apiKey, String userId, String endpoint) {
        if (apiKey != null) {
            LimitRule rule = lookup(byApiKeyAndEndpoint, apiKey, endpoint);
            if (rule != null) {
                return rule;
            }
        }
        if (userId != null) {
            LimitRule rule = lookup(byUserAndEndpoint, userId, endpoint);
            if (rule != null) {
                return rule;
            }
        }

        LimitRule endpointRule = byEndpoint.get(endpoint);
        if (endpointRule != null) {
            return endpointRule;
        }

        if (apiKey != null) {
            LimitRule rule = byApiKey.get(apiKey);
            if (rule != null) {
                return rule;
            }
        }
        if (userId != null) {
            LimitRule rule = byUser.get(userId);
            if (rule != null) {
                return rule;
            }
        }

        return defaultRule;
    }

    public LimitRule defaultRule() {
        return defaultRule;
    }

    private static LimitRule lookup(Map<String, Map<String, LimitRule>> table,
                                    String identifier,
                                    String endpoint) {
        Map<String, LimitRule> forIdentifier = table.get(identifier);
        return forIdentifier == null ? null : forIdentifier.get(endpoint);
    }
}
