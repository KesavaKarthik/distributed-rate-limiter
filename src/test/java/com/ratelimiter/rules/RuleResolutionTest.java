package com.ratelimiter.rules;

import com.ratelimiter.limiter.LuaScripts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Resolution is pure in-memory, so this test needs no Redis and no Spring
 * context — which is itself the claim being made: nothing on the request path
 * between "a request arrived" and "here is its rule" touches the network.
 *
 * <p>Strategies are constructed for real (against a template that is never
 * called) so the scopes and key prefixes under test are the ones production
 * builds, not a fixture's idea of them.
 */
class RuleResolutionTest {

    private static final String ENDPOINT = "/api/strict";
    private static final String OTHER_ENDPOINT = "/api/other";
    private static final String USER = "alice";
    private static final String API_KEY = "key-1";

    private RuleIndex index;

    @BeforeEach
    void buildIndex() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setAlgorithm("tb");
        properties.setCapacity(10);
        properties.setRefillRate(5.0);

        RateLimiterProperties.Rules rules = properties.getRules();
        rules.setEndpoints(Map.of(ENDPOINT, bucket(100)));
        rules.setUsers(Map.of(USER, bucket(200)));
        rules.setApiKeys(Map.of(API_KEY, bucket(300)));
        rules.setUserEndpoints(Map.of(USER, Map.of(ENDPOINT, bucket(400))));
        rules.setApiKeyEndpoints(Map.of(API_KEY, Map.of(ENDPOINT, bucket(500))));

        StrategyFactory factory = new StrategyFactory(new StringRedisTemplate(), new LuaScripts());
        index = new RuleIndexFactory().identityRuleIndex(properties, factory);
    }

    @Test
    void apiKeyAndEndpoint_isTheMostSpecificRung() {
        assertEquals("api-key+endpoint[" + API_KEY + " " + ENDPOINT + "]",
                index.resolve(API_KEY, USER, ENDPOINT).id());
    }

    @Test
    void userAndEndpoint_beatsEndpoint_whenNoApiKeyRuleMatches() {
        assertEquals("user+endpoint[" + USER + " " + ENDPOINT + "]",
                index.resolve(null, USER, ENDPOINT).id());
        // An api key that has no rule of its own must not shadow the user rung.
        assertEquals("user+endpoint[" + USER + " " + ENDPOINT + "]",
                index.resolve("unknown-key", USER, ENDPOINT).id());
    }

    @Test
    void endpoint_beatsTheBareIdentityRungs() {
        // Both a user rule and an api-key rule match this caller, but the endpoint
        // rung sits above both, so neither is consulted.
        assertEquals("endpoint[" + ENDPOINT + "]",
                index.resolve("unknown-key", "unknown-user", ENDPOINT).id());
    }

    @Test
    void apiKey_beatsUser_offTheRuledEndpoints() {
        assertEquals("api-key[" + API_KEY + "]", index.resolve(API_KEY, USER, OTHER_ENDPOINT).id());
        assertEquals("user[" + USER + "]", index.resolve(null, USER, OTHER_ENDPOINT).id());
    }

    @Test
    void everyRequestResolves_theDefaultIsTheFloor() {
        assertSame(index.defaultRule(), index.resolve(null, null, OTHER_ENDPOINT));
        assertSame(index.defaultRule(), index.resolve("unknown-key", "unknown-user", OTHER_ENDPOINT));
        assertEquals("default", index.defaultRule().id());
    }

    /**
     * THE TRADEOFF, asserted on purpose. The user+endpoint rule is 4x more
     * permissive than the endpoint rule it outranks, and it still wins. First
     * match on specificity REPLACES; it does not take the tighter of the two.
     * If this assertion ever flips to a minimum, overrides stop being able to
     * raise a ceiling, which is the direction they are actually used in.
     */
    @Test
    void aMoreSpecificRule_replacesRatherThanTightens() {
        LimitRule endpointRule = index.resolve(null, "unknown-user", ENDPOINT);
        LimitRule overridden = index.resolve(null, USER, ENDPOINT);

        assertEquals("endpoint[" + ENDPOINT + "]", endpointRule.id());
        assertEquals("user+endpoint[" + USER + " " + ENDPOINT + "]", overridden.id());
    }

    /** Tiers must not share a bucket, or an override would inherit the counter it replaced. */
    @Test
    void eachTierGetsItsOwnKeyspace() {
        assertEquals("ratelimit:tb:user:", index.defaultRule().keyFor(""));
        assertEquals("ratelimit:tb:ep|" + ENDPOINT + ":", index.resolve(null, null, ENDPOINT).keyFor(""));
        assertEquals("ratelimit:tb:u:" + USER, index.resolve(null, USER, OTHER_ENDPOINT).keyFor(USER));
        assertEquals("ratelimit:tb:k:" + API_KEY, index.resolve(API_KEY, null, OTHER_ENDPOINT).keyFor(API_KEY));
    }

    private static RuleProperties bucket(int capacity) {
        RuleProperties rule = new RuleProperties();
        rule.setAlgorithm("tb");
        rule.setCapacity(capacity);
        rule.setRefillRate(capacity / 2.0);
        return rule;
    }
}
