package com.ratelimiter.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who is calling, as far as the limiter needs to know.
 *
 * <p>Resolved once per request and stashed on the request, so the fine-grained
 * filter does no parsing of its own.
 *
 * @param apiKey    null when no key was presented
 * @param userId    null when the caller is unauthenticated
 * @param principal what the counter is keyed on: the key, else the user, else the
 *                  remote address. Never null, so every request is countable.
 */
public record RequestIdentity(String apiKey, String userId, String principal) {

    private static final String ATTRIBUTE = RequestIdentity.class.getName();

    /**
     * Falls back to the remote address so an unauthenticated caller is still
     * limited rather than unlimited. Note that key and user identifiers share one
     * namespace in the counter key; they are assumed disjoint, which holds for
     * anything key-shaped (a UUID, a prefixed token) against a user id.
     */
    static RequestIdentity of(String apiKey, String userId, HttpServletRequest request) {
        String principal = apiKey != null ? apiKey
                : userId != null ? userId
                : request.getRemoteAddr();
        return new RequestIdentity(apiKey, userId, principal);
    }

    void storeOn(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, this);
    }

    /** Never null: the identity filter runs ahead of every filter that reads this. */
    public static RequestIdentity from(HttpServletRequest request) {
        RequestIdentity identity = (RequestIdentity) request.getAttribute(ATTRIBUTE);
        return identity != null ? identity : of(null, null, request);
    }
}
