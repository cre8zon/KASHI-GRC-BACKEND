package com.kashi.grc.enquiry.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counter for the public enquiry form, in memory.
 *
 * In memory is deliberate: the form is low-volume, a restart forgetting the
 * window costs nothing, and it keeps a public endpoint from needing Redis to
 * stay up. With several app instances each keeps its own window, so the real
 * ceiling is limit × instances — still far below anything that hurts.
 */
@Component
public class EnquiryRateLimiter {

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** Records the hit and returns true when {@code key} is still within {@code limit} per {@code window}. */
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
        if (hits.size() > 50_000) prune(cutoff);   // keep the map from growing without bound
        return true;
    }

    private void prune(Instant cutoff) {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(cutoff);
            }
        });
    }
}
