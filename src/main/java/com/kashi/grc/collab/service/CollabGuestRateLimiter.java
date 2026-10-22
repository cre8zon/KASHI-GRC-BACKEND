package com.kashi.grc.collab.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counter for the public call-link endpoints (join page, ask
 * to join, the guest's poll), in memory.
 *
 * In memory on purpose, like the enquiry form's: a restart forgetting the
 * window costs nothing, and a public endpoint should not need Redis to stay
 * up. With several instances each keeps its own window, so the real ceiling
 * is limit × instances — still far too low to guess an 80-bit link code.
 */
@Component
public class CollabGuestRateLimiter {

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** Records the hit; true while {@code key} is within {@code limit} per {@code window}. */
    public boolean allow(String key, int limit, Duration window) {
        if (key == null) return true;
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
        }
        if (hits.size() > 50_000) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(cutoff);
                }
            });
        }
        return true;
    }
}
