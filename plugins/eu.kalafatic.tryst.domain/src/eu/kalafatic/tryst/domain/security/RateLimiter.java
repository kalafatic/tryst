package eu.kalafatic.tryst.domain.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure Java 21 thread-safe sliding window rate limiter.
 */
public class RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final Map<String, Deque<Long>> clientRequests = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowSeconds) {
        this.maxRequests = Math.max(1, maxRequests);
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
    }

    public boolean allowRequest(String key) {
        if (key == null || key.isBlank()) {
            key = "anonymous";
        }
        long now = System.currentTimeMillis();
        long windowStart = now - windowMillis;

        Deque<Long> timestamps = clientRequests.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < maxRequests) {
                timestamps.addLast(now);
                return true;
            }
            return false;
        }
    }

    public void reset(String key) {
        if (key != null) {
            clientRequests.remove(key);
        }
    }

    public void clear() {
        clientRequests.clear();
    }
}
