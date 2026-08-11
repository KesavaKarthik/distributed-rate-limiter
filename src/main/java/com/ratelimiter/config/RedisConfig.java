package com.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis wiring. The connection factory itself is auto-configured by Boot from
 * spring.data.redis.* (host/port/timeout/pool) — we only declare the template
 * we want to work with, so the connection settings stay in one place: the yml.
 */
@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate, not a generic RedisTemplate&lt;Object, Object&gt;: our keys
     * and values are already strings, and the default JDK serializer would write
     * opaque bytes — unreadable from redis-cli while watching a race in flight,
     * and unusable by Lua, which can only compute on strings/numbers.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    // The token-bucket Lua script bean moved to TokenBucketStrategy — a strategy
    // owns the code that manipulates its own state. Declaring three unrelated
    // scripts here would spread the tb/swl/swc split across two places.
}
