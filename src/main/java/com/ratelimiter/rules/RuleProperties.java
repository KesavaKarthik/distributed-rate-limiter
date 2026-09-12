package com.ratelimiter.rules;

/**
 * One rule as written in yaml, before validation.
 *
 * <p>Mutable with boxed fields because this is a binding target: a null tells
 * {@code RuleIndexFactory} the operator omitted the field, which a primitive
 * {@code 0} could not. Every field is optional here and mandatory-or-forbidden
 * after validation, depending on the algorithm.
 */
public class RuleProperties {

    private String algorithm;

    // Token bucket.
    private Integer capacity;
    private Double refillRate;

    // Sliding window, log and counter.
    private Integer limit;
    private Long windowMs;

    /** Null means "inherit the global default", not "fail open". */
    private FailureMode failureMode;

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Double getRefillRate() {
        return refillRate;
    }

    public void setRefillRate(Double refillRate) {
        this.refillRate = refillRate;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Long getWindowMs() {
        return windowMs;
    }

    public void setWindowMs(Long windowMs) {
        this.windowMs = windowMs;
    }

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public void setFailureMode(FailureMode failureMode) {
        this.failureMode = failureMode;
    }
}
