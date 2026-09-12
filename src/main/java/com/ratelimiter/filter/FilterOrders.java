package com.ratelimiter.filter;

/**
 * Where the limiter sits in the servlet filter chain.
 *
 * <p>Two-phase on purpose. The coarse per-address limit must run <b>before</b>
 * authentication, because a caller who never authenticates is exactly the one a
 * coarse limit exists to stop — running it after auth would mean spending auth
 * work on traffic we intended to drop. The fine per-user/per-key limit must run
 * <b>after</b> authentication, because it cannot resolve a rule until it knows
 * who is calling.
 *
 * <p>The numbers are offsets from Spring Security's
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER}, which is -100. Security is not
 * on the classpath yet; {@link com.ratelimiter.security.IdentityFilter} occupies
 * that slot. Adding the starter later means deleting that filter, not renumbering
 * these — the coarse limit is already ahead of the chain and the fine limit
 * already behind it.
 */
public final class FilterOrders {

    /** Spring Security's own chain order, mirrored so the intent survives the dependency being absent. */
    public static final int AUTHENTICATION = -100;

    public static final int IP_RATE_LIMIT = AUTHENTICATION - 5;
    public static final int IDENTITY_RATE_LIMIT = AUTHENTICATION + 5;

    private FilterOrders() {
    }
}
