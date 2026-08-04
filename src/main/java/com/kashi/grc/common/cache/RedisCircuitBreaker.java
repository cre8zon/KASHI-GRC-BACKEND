package com.kashi.grc.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Circuit breaker guarding every Redis-backed cache in the app (one shared,
 * global circuit — a real Redis outage takes down the whole connection, not
 * one cache region at a time, so a single breaker matches the actual failure
 * mode instead of tracking N independent ones).
 *
 * WHY HAND-ROLLED INSTEAD OF RESILIENCE4J: this codebase already has an
 * established pattern for exactly this kind of resilience — Kafka doesn't
 * use resilience4j either, it uses Spring Kafka's own DefaultErrorHandler +
 * DLT (see KafkaConfig). Adding a new dependency for one circuit breaker
 * when the state machine involved is ~30 lines isn't worth the added
 * surface area. If more circuit breakers are needed elsewhere later,
 * revisit — at that point resilience4j earns its keep.
 *
 * STATES:
 *   CLOSED    — normal operation, every call attempts Redis.
 *   OPEN      — Redis has failed FAILURE_THRESHOLD times in a row; every
 *               call skips Redis entirely and falls straight through to the
 *               caller's DB path for OPEN_DURATION_MS, no wasted connection
 *               attempts.
 *   HALF_OPEN — after OPEN_DURATION_MS elapses, the NEXT call is allowed
 *               through as a probe. Success -> CLOSED. Failure -> OPEN again
 *               (resets the timer).
 */
@Slf4j
@Component
public class RedisCircuitBreaker {

    private static final int  FAILURE_THRESHOLD = 5;
    private static final long OPEN_DURATION_MS  = 30_000; // 30s cooldown before probing again

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong    openedAt            = new AtomicLong(0);

    /** Call before attempting a Redis operation. True = go ahead, false = skip straight to DB. */
    public boolean allowRequest() {
        if (state == State.CLOSED) return true;

        if (state == State.OPEN) {
            long elapsed = System.currentTimeMillis() - openedAt.get();
            if (elapsed >= OPEN_DURATION_MS) {
                // Cooldown elapsed — let exactly one probe through.
                synchronized (this) {
                    if (state == State.OPEN) {
                        state = State.HALF_OPEN;
                        log.info("[CACHE-CIRCUIT] OPEN -> HALF_OPEN | probing Redis after {}ms cooldown", elapsed);
                        return true;
                    }
                }
            }
            return false;
        }

        // HALF_OPEN: only the probe request that flipped us here should proceed;
        // everything else concurrent with it skips Redis until the probe resolves.
        return false;
    }

    public void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("[CACHE-CIRCUIT] {} -> CLOSED | Redis reachable again", state);
        }
        consecutiveFailures.set(0);
        state = State.CLOSED;
    }

    public void recordFailure() {
        if (state == State.HALF_OPEN) {
            // Probe failed — back to OPEN, restart the cooldown.
            trip();
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD && state == State.CLOSED) {
            trip();
        }
    }

    private void trip() {
        state = State.OPEN;
        openedAt.set(System.currentTimeMillis());
        log.warn("[CACHE-CIRCUIT] -> OPEN | Redis failures reached threshold, skipping Redis for {}ms",
                OPEN_DURATION_MS);
    }

    /** For metrics/health endpoints. */
    public String currentState() {
        return state.name();
    }
}