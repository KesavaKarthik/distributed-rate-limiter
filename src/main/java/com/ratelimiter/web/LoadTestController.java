package com.ratelimiter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Three endpoints whose handlers do nothing, so the load test measures the
 * limiter and not the application.
 *
 * <p>They differ only in which rule resolves to them: {@code /api/burst} a
 * generous token bucket, {@code /api/strict} a tight sliding window log that is
 * fail-closed, and {@code /api/baseline} nothing at all — it is in
 * {@code ratelimiter.exempt-paths}, which makes it the control.
 */
@RestController
public class LoadTestController {

    @GetMapping("/api/baseline")
    public String baseline() {
        return "ok";
    }

    @GetMapping("/api/burst")
    public String burst() {
        return "ok";
    }

    @GetMapping("/api/strict")
    public String strict() {
        return "ok";
    }
}
