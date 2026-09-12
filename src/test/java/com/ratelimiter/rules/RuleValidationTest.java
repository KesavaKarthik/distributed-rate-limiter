package com.ratelimiter.rules;

import com.ratelimiter.limiter.LuaScripts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A malformed rule must stop the application, not become a wrong limit.
 *
 * <p>Rate limits fail quietly when they fail: a typo that halves a ceiling looks
 * exactly like normal operation until someone is throttled who should not have
 * been. Validating at startup converts that into the one failure mode that is
 * impossible to miss.
 */
class RuleValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Never used: every case here is decided before a command is issued.
            .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(mock(RedisConnectionFactory.class)))
            .withBean(LuaScripts.class)
            .withBean(StrategyFactory.class)
            .withUserConfiguration(RuleIndexFactory.class)
            .withPropertyValues(
                    "ratelimiter.algorithm=tb",
                    "ratelimiter.capacity=10",
                    "ratelimiter.refill-rate=5.0");

    @Test
    void aValidConfiguration_startsAndIndexesEveryTier() {
        runner.withPropertyValues(
                        "ratelimiter.rules.endpoints.[/api/x].algorithm=swl",
                        "ratelimiter.rules.endpoints.[/api/x].limit=10",
                        "ratelimiter.rules.endpoints.[/api/x].window-ms=1000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RuleIndex index = context.getBean("identityRuleIndex", RuleIndex.class);
                    assertThat(index.resolve(null, null, "/api/x").id()).isEqualTo("endpoint[/api/x]");
                });
    }

    @Test
    void unknownAlgorithm_failsStartupAndNamesTheRegisteredTags() {
        runner.withPropertyValues("ratelimiter.rules.users.bob.algorithm=leaky-bucket")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("user[bob]")
                        .hasMessageContaining("leaky-bucket")
                        .hasMessageContaining("tb"));
    }

    @Test
    void anOverrideMissingItsAlgorithm_failsRatherThanInheritingOne() {
        runner.withPropertyValues("ratelimiter.rules.users.bob.capacity=50")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("user[bob]")
                        .hasMessageContaining("algorithm"));
    }

    @Test
    void missingRequiredFieldForTheChosenAlgorithm_failsStartup() {
        runner.withPropertyValues(
                        "ratelimiter.rules.users.bob.algorithm=swl",
                        "ratelimiter.rules.users.bob.limit=10")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("user[bob]")
                        .hasMessageContaining("window-ms"));
    }

    /** The typo this catches: meaning swl, writing tb, and getting a bucket that ignores the limit. */
    @Test
    void aFieldTheAlgorithmCannotUse_failsStartup() {
        runner.withPropertyValues(
                        "ratelimiter.rules.users.bob.algorithm=tb",
                        "ratelimiter.rules.users.bob.capacity=50",
                        "ratelimiter.rules.users.bob.refill-rate=25",
                        "ratelimiter.rules.users.bob.limit=10")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("user[bob]")
                        .hasMessageContaining("limit"));
    }

    @Test
    void nonsensicalNumbers_failStartup() {
        runner.withPropertyValues(
                        "ratelimiter.rules.users.bob.algorithm=tb",
                        "ratelimiter.rules.users.bob.capacity=0",
                        "ratelimiter.rules.users.bob.refill-rate=1")
                .run(context -> assertThat(context).getFailure().hasMessageContaining("capacity must be >= 1"));
    }

    @Test
    void anEndpointThatIsNotAPath_failsStartup() {
        runner.withPropertyValues(
                        "ratelimiter.rules.endpoints.[api/x].algorithm=tb",
                        "ratelimiter.rules.endpoints.[api/x].capacity=1",
                        "ratelimiter.rules.endpoints.[api/x].refill-rate=1")
                .run(context -> assertThat(context).getFailure().hasMessageContaining("absolute path"));
    }

    /**
     * The global default carries config for all three algorithms at once so the
     * active one is a single-property switch; only overrides are checked for
     * fields their algorithm cannot use.
     */
    @Test
    void theGlobalDefaultMayCarryConfigForEveryAlgorithm() {
        runner.withPropertyValues("ratelimiter.limit=100", "ratelimiter.window-ms=60000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The coarse tier is built and validated whether or not it is switched on, so
     * enabling it is never the moment a config error is discovered.
     */
    @Test
    void theIpTierIsValidatedEvenWhileDisabled() {
        runner.withPropertyValues(
                        "ratelimiter.ip.enabled=false",
                        "ratelimiter.ip.rule.algorithm=tb",
                        "ratelimiter.ip.rule.capacity=0",
                        "ratelimiter.ip.rule.refill-rate=1")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("ip-default")
                        .hasMessageContaining("capacity must be >= 1"));
    }

    @Test
    void anUnconfiguredIpTier_fallsBackToTheGlobalDefaultRuleAppliedPerAddress() {
        runner.run(context -> {
            RuleIndex ip = context.getBean("ipRuleIndex", RuleIndex.class);
            assertThat(ip.resolve(null, null, "/anything").keyFor("10.0.0.1"))
                    .isEqualTo("ratelimit:tb:ip:10.0.0.1");
        });
    }

    @Test
    void theIpTierResolvesItsOwnEndpointRulesAndKeepsThemOutOfTheIdentityKeyspace() {
        runner.withPropertyValues(
                        "ratelimiter.ip.enabled=true",
                        "ratelimiter.ip.rule.algorithm=tb",
                        "ratelimiter.ip.rule.capacity=100",
                        "ratelimiter.ip.rule.refill-rate=50",
                        "ratelimiter.ip.endpoints.[/api/x].algorithm=tb",
                        "ratelimiter.ip.endpoints.[/api/x].capacity=10",
                        "ratelimiter.ip.endpoints.[/api/x].refill-rate=5")
                .run(context -> {
                    RuleIndex ip = context.getBean("ipRuleIndex", RuleIndex.class);
                    assertThat(ip.resolve(null, null, "/api/x").id()).isEqualTo("ip+endpoint[/api/x]");
                    assertThat(ip.resolve(null, null, "/api/x").keyFor("10.0.0.1"))
                            .isEqualTo("ratelimit:tb:ipe|/api/x:10.0.0.1");
                    assertThat(ip.resolve(null, null, "/other").id()).isEqualTo("ip-default");
                });
    }

    /**
     * Failure mode is the one field an override inherits when it is silent, and
     * that is not a merge: it is a policy default, not part of the limit spec.
     */
    @Test
    void failureModeIsPerRule_andFallsBackToTheGlobalDefaultWhenUnstated() {
        runner.withPropertyValues(
                        "ratelimiter.failure-mode=fail-open",
                        "ratelimiter.rules.endpoints.[/api/cheap].algorithm=tb",
                        "ratelimiter.rules.endpoints.[/api/cheap].capacity=10",
                        "ratelimiter.rules.endpoints.[/api/cheap].refill-rate=5",
                        "ratelimiter.rules.endpoints.[/api/costly].algorithm=tb",
                        "ratelimiter.rules.endpoints.[/api/costly].capacity=10",
                        "ratelimiter.rules.endpoints.[/api/costly].refill-rate=5",
                        "ratelimiter.rules.endpoints.[/api/costly].failure-mode=fail-closed")
                .run(context -> {
                    RuleIndex index = context.getBean("identityRuleIndex", RuleIndex.class);
                    assertThat(index.resolve(null, null, "/api/cheap").failureMode())
                            .isEqualTo(FailureMode.FAIL_OPEN);
                    assertThat(index.resolve(null, null, "/api/costly").failureMode())
                            .isEqualTo(FailureMode.FAIL_CLOSED);
                    assertThat(index.defaultRule().failureMode()).isEqualTo(FailureMode.FAIL_OPEN);
                });
    }
}
