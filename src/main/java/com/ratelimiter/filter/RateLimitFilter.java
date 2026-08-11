package com.ratelimiter.filter;

import com.ratelimiter.limiter.RateLimitResult;
import com.ratelimiter.limiter.RateLimiterStrategy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-client rate limit on every request. Identity is X-Client-Id, else the
 * remote address.
 *
 * <p>Depends on the interface, not a concrete algorithm: nothing token-bucket
 * shaped is left here. Swapping in another algorithm changes which bean is
 * injected and nothing else in this class.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final String SCOPE = "user";

    private final RateLimiterStrategy limiter;

    public RateLimitFilter(RateLimiterStrategy limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String clientId = resolveClientId(request);

        // The strategy builds the key, so its algo tag always matches the state.
        String key = limiter.keyFor(SCOPE, clientId);
        RateLimitResult result = limiter.tryAcquire(key);

        // Free: the script already computed this from its snapshot.
        response.setHeader("X-RateLimit-Remaining", Long.toString(result.remaining()));

        if (result.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        // From the algorithm's real token deficit. The old 1/refillRate guess was
        // only right for a token bucket, and only when it was exactly empty.
        if (result.hasRetryTime()) {
            long retryAfterSeconds = Math.max(1, (long) Math.ceil(result.retryAfterMillis() / 1000.0));
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }

        response.setStatus(TOO_MANY_REQUESTS);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
    }

    private String resolveClientId(HttpServletRequest request) {
        String clientHeader = request.getHeader("X-Client-Id");
        if (clientHeader != null && !clientHeader.isBlank()) {
            return clientHeader;
        }
        return request.getRemoteAddr();
    }
}
