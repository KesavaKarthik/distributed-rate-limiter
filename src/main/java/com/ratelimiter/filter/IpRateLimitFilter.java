package com.ratelimiter.filter;

import com.ratelimiter.rules.LimitRule;
import com.ratelimiter.rules.RateLimiterProperties;
import com.ratelimiter.rules.RuleIndex;
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
 * The coarse limit: per remote address, before anyone has been authenticated.
 *
 * <p>Placed ahead of the authentication slot deliberately. A caller who never
 * successfully authenticates is precisely the traffic a coarse limit exists to
 * shed, and shedding it after auth would mean spending the authentication work
 * first. The cost is that it cannot tell two callers behind one NAT apart — which
 * is why it is coarse, and why the per-user limit still runs afterwards.
 *
 * <p>Disabled by default. Its rule table is still built and validated at startup
 * so enabling it is never the moment a config error is discovered.
 */
@Component
@Order(FilterOrders.IP_RATE_LIMIT)
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RuleIndex rules;
    private final RateLimitGate gate;
    private final ExemptPaths exempt;
    private final boolean enabled;

    public IpRateLimitFilter(@Qualifier("ipRuleIndex") RuleIndex rules,
                             RateLimitGate gate,
                             ExemptPaths exempt,
                             RateLimiterProperties properties) {
        this.rules = rules;
        this.gate = gate;
        this.exempt = exempt;
        this.enabled = properties.getIp().isEnabled();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || exempt.covers(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // No identity yet by design, so only the endpoint tier and the coarse
        // default can match. getRemoteAddr() is the peer address; behind a proxy
        // that is the proxy, and honouring X-Forwarded-For would mean trusting a
        // header the caller controls. That belongs to the proxy's own config.
        LimitRule rule = rules.resolve(null, null, request.getRequestURI());

        if (gate.allow(response, rule, request.getRemoteAddr())) {
            filterChain.doFilter(request, response);
        }
    }
}
