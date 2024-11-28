package com.sparrowwallet.tern.http.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class RateLimiter {
    private final int maxTokens;
    private final long refillIntervalMillis;
    private final AtomicInteger tokens;
    private long lastRefillTimestamp;
    private final ReentrantLock lock = new ReentrantLock();

    public RateLimiter(int maxTokens, long refillIntervalMillis) {
        this.maxTokens = maxTokens;
        this.refillIntervalMillis = refillIntervalMillis;
        this.tokens = new AtomicInteger(maxTokens);
        this.lastRefillTimestamp = System.currentTimeMillis();
    }

    public boolean tryAcquire() {
        refill();
        if (tokens.get() > 0) {
            tokens.decrementAndGet();
            return true;
        }
        return false;
    }

    private void refill() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTimestamp;
            if (elapsed > refillIntervalMillis) {
                int tokensToAdd = (int) (elapsed / refillIntervalMillis);
                tokens.set(Math.min(maxTokens, tokens.get() + tokensToAdd));
                lastRefillTimestamp = now;
            }
        } finally {
            lock.unlock();
        }
    }
}
