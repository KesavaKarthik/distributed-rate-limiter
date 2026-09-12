package com.ratelimiter.filter;

import com.ratelimiter.rules.RateLimiterProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Paths the limiter never touches.
 *
 * <p>Exists so the load test can measure a baseline through the same JVM, the
 * same connector and the same warmed-up code path as a limited endpoint, with
 * only the Redis round trip removed. A whole-application disable switch would
 * have needed a second run, and two runs cannot be subtracted from each other
 * with any confidence.
 */
@Component
public class ExemptPaths {

    private final Set<String> paths;

    public ExemptPaths(RateLimiterProperties properties) {
        this.paths = Set.copyOf(properties.getExemptPaths());
    }

    public boolean covers(String requestUri) {
        return !paths.isEmpty() && paths.contains(requestUri);
    }
}
