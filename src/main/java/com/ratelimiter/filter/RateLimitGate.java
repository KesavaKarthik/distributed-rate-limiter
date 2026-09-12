package com.ratelimiter.filter;

import com.ratelimiter.limiter.RateLimitResult;
import com.ratelimiter.resilience.DegradedLogger;
import com.ratelimiter.resilience.RedisCircuitBreaker;
import com.ratelimiter.rules.FailureMode;
import com.ratelimiter.rules.LimitRule;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Applies one resolved rule and writes the response when it denies.
 *
 * <p>Both limiter filters delegate here so the degradation policy exists once.
 * The contract this class exists to hold: <b>no Redis failure escapes it.</b> A
 * limiter whose backing store is down must degrade to a decision, never to a 500
 * — otherwise the component added for protection becomes the outage.
 *
 * <p>Three outcomes are distinguished on the wire, and keeping them distinct is
 * the point: <b>200</b> allowed, <b>429</b> you exceeded your limit, <b>503</b>
 * the limiter could not decide and this rule is fail-closed. Collapsing the last
 * two into 429 would make degradation indistinguishable from enforcement in every
 * metric downstream, including the k6 run.
 */
@Component
public class RateLimitGate {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVICE_UNAVAILABLE = 503;

    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final String HEADER_RULE = "X-RateLimit-Rule";
    private static final String HEADER_DEGRADED = "X-RateLimit-Degraded";

    private final RedisCircuitBreaker breaker;
    private final DegradedLogger degradedLogger;

    public RateLimitGate(RedisCircuitBreaker breaker, DegradedLogger degradedLogger) {
        this.breaker = breaker;
        this.degradedLogger = degradedLogger;
    }

    /**
     * @return true to continue the chain; false when this method has already
     *         written the whole response
     */
    public boolean allow(HttpServletResponse response, LimitRule rule, String principal) throws IOException {
        response.setHeader(HEADER_RULE, rule.id());

        if (!breaker.allowAttempt()) {
            return degrade(response, rule, "circuit open", null);
        }

        RateLimitResult result;
        try {
            result = rule.tryAcquire(principal);
            breaker.recordSuccess();
        } catch (DataAccessException e) {
            // Spring translates Lettuce timeouts and connection failures into this
            // family, so it is the outage case: expected, and the breaker counts it.
            breaker.recordFailure();
            return degrade(response, rule, "redis unavailable: " + e.getClass().getSimpleName(), e);
        } catch (RuntimeException e) {
            // Not an outage — a malformed script reply or a bug. Still must not
            // 500 the request, but it is not evidence that Redis is down, so the
            // breaker is left alone and the severity is higher.
            degradedLogger.degraded(rule.id(), rule.failureMode(), "limiter fault", e);
            return apply(response, rule.failureMode(), "fault");
        }

        response.setHeader(HEADER_REMAINING, Long.toString(result.remaining()));

        if (result.allowed()) {
            return true;
        }

        // From the algorithm's real deficit, not a guess: only the script knows
        // when the next token or the next window edge actually arrives.
        if (result.hasRetryTime()) {
            response.setHeader(HEADER_RETRY_AFTER, Long.toString(retryAfterSeconds(result.retryAfterMillis())));
        }

        response.setStatus(TOO_MANY_REQUESTS);
        writeJson(response, "rate limit exceeded");
        return false;
    }

    private boolean degrade(HttpServletResponse response, LimitRule rule, String reason, Throwable cause)
            throws IOException {
        degradedLogger.degraded(rule.id(), rule.failureMode(), reason, cause);
        return apply(response, rule.failureMode(), reason);
    }

    private boolean apply(HttpServletResponse response, FailureMode mode, String reason) throws IOException {
        response.setHeader(HEADER_DEGRADED, mode.isOpen() ? "fail-open" : "fail-closed");

        if (mode.isOpen()) {
            return true;
        }

        response.setStatus(SERVICE_UNAVAILABLE);
        response.setHeader(HEADER_RETRY_AFTER, "1");
        writeJson(response, "rate limiter unavailable (" + reason + ")");
        return false;
    }

    /** Retry-After is whole seconds, and zero would invite an immediate retry. */
    private static long retryAfterSeconds(long retryAfterMillis) {
        return Math.max(1, (long) Math.ceil(retryAfterMillis / 1000.0));
    }

    private static void writeJson(HttpServletResponse response, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
