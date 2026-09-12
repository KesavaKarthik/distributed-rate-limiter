package com.ratelimiter.security;

import com.ratelimiter.filter.FilterOrders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stands in for authentication: reads the caller identity off headers and puts it
 * on the request.
 *
 * <p>It authenticates nothing — it establishes the <i>boundary</i>. The design
 * point Phase 2 needed was that a coarse limit runs before identity is known and
 * a fine limit runs after; that ordering is what this filter makes real. Swapping
 * in {@code spring-boot-starter-security} means replacing this class with a real
 * chain at the same {@link FilterOrders#AUTHENTICATION} slot and reading the
 * {@code SecurityContext} instead of the headers. Nothing else moves.
 *
 * <p>{@code X-Client-Id} is still honoured as a user id so Phase 1 callers and
 * tests keep working unchanged.
 */
@Component
@Order(FilterOrders.AUTHENTICATION)
public class IdentityFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String USER_HEADER = "X-User-Id";
    private static final String LEGACY_USER_HEADER = "X-Client-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = header(request, API_KEY_HEADER);
        String userId = header(request, USER_HEADER);
        if (userId == null) {
            userId = header(request, LEGACY_USER_HEADER);
        }

        RequestIdentity.of(apiKey, userId, request).storeOn(request);

        filterChain.doFilter(request, response);
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }
}
