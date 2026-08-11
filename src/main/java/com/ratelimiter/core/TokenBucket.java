package com.ratelimiter.core;

/**
 * Single-key in-memory token bucket. One instance guards ONE client's rate limit.
 *
 * <p><b>Phase 0 artifact — not on the request path.</b> Kept because
 * {@code TokenBucketRaceTest} still proves something: drop the
 * {@code synchronized} and it goes red. That is the evidence a JVM monitor closes
 * this race when all racers share a heap — and therefore why
 * {@code DistributedRaceTest}, the same test across two JVMs, needed Redis instead.
 */
public class TokenBucket {

    private final int capacity;
    private final double refillRate; // tokens per second

    private double tokens;
    private long lastRefillTimestamp; // nanoTime at last refill

    public TokenBucket(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }
    
    public synchronized boolean tryConsume() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillTimestamp;
        double tokensToAdd = (elapsed / 1_000_000_000.0) * refillRate;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillTimestamp = now;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        } else {
            return false;
        }
    }

    public int getCapacity() {
        return capacity;
    }

    public double getRefillRate() {
        return refillRate;
    }

    // Package-private/test-visible accessors — useful for assertions in tests.
    double getTokens() {
        return tokens;
    }

    long getLastRefillTimestamp() {
        return lastRefillTimestamp;
    }
}
