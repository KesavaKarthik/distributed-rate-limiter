package com.ratelimiter.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenBucketRaceTest {

    @Test
    void concurrentTryConsume_shouldNeverAllowMoreThanCapacity() throws InterruptedException {
        int capacity = 100;
        // refillRate = 0 so no tokens regenerate mid-test — isolates the race
        // to the decrement itself, independent of refill timing.
        TokenBucket bucket = new TokenBucket(capacity, 0.0);

        int threadCount = 1000; // far more attempts than available tokens
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startSignal.await(); // all threads wait here, then release together
                    if (bucket.tryConsume()) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        startSignal.countDown(); // release all threads at once
        doneSignal.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(capacity, allowedCount.get(),
                "allowed requests should never exceed bucket capacity");
    }
}
