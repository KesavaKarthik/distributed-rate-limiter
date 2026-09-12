package com.ratelimiter.filter;

import com.ratelimiter.rules.LimitRule;
import com.ratelimiter.rules.RuleIndex;
import com.ratelimiter.security.RequestIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The fine-grained limit: per api-key, per user, per endpoint, or the global
 * default — whichever rule is most specific.
 *
 * <p>Runs after the authentication slot because it cannot resolve a rule until it
 * knows who is calling. Phase 1's single {@code RateLimitFilter} with one
 * hard-coded scope grew into this; the request path did not get longer, because
 * resolution is map lookups in memory and the Redis call is still exactly one.
 */
@Component
@Order(FilterOrders.IDENTITY_RATE_LIMIT)
public class IdentityRateLimitFilter extends OncePerRequestFilter {

    private final RuleIndex rules;
    private final RateLimitGate gate;
    private final ExemptPaths exempt;

    public IdentityRateLimitFilter(@Qualifier("identityRuleIndex") RuleIndex rules,
                                   RateLimitGate gate,
                                   ExemptPaths exempt) {
        this.rules = rules;
        this.gate = gate;
        this.exempt = exempt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return exempt.covers(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        RequestIdentity identity = RequestIdentity.from(request);

        // Exact path, no pattern matching: a hash lookup, and a rule table that
        // cannot be ambiguous about which of two overlapping patterns won.
        LimitRule rule = rules.resolve(identity.apiKey(), identity.userId(), request.getRequestURI());

        if (gate.allow(response, rule, identity.principal())) {
            filterChain.doFilter(request, response);
        }
    }
}
