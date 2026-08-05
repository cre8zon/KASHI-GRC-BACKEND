package com.kashi.grc.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Wraps a real RedisCache with three concerns layered on top, none of which
 * require touching a single @Cacheable call site:
 *
 *  1. CIRCUIT BREAKER — every get/put/evict/clear checks RedisCircuitBreaker
 *     first. When open, Redis is skipped entirely (no connection attempt, no
 *     wasted latency on a doomed call) and the request falls straight through
 *     via the caller's Cache.get(key, valueLoader) path, exactly like a
 *     normal cache miss would.
 *
 *  2. STAMPEDE PROTECTION — implemented in get(key, valueLoader), which is
 *     the exact hook Spring's @Cacheable uses under the hood (CacheAspectSupport
 *     calls cache.get(key, () -> invokeAnnotatedMethod())). On a miss, this
 *     tries to acquire a short-lived SETNX lock in Redis for that key before
 *     calling valueLoader. If it gets the lock, it's the one request that
 *     repopulates; everyone else concurrently missing the same key polls the
 *     cache briefly instead of also hitting the DB. Never blocks indefinitely
 *     — a bounded poll with a direct-load fallback if the lock holder hasn't
 *     finished in time, so a stuck lock can never wedge every caller.
 *
 *  3. METRICS — hit/miss/put/evict counters and a Redis-call-latency timer,
 *     tagged by cache name, published through the MeterRegistry your pom
 *     already wires to Prometheus (micrometer-registry-prometheus). Spring
 *     Boot's actuator auto-instruments several cache providers out of the
 *     box (Caffeine, EhCache, Hazelcast, JCache) but NOT RedisCacheManager —
 *     there's no built-in binder for it, which is why this exists.
 */
@Slf4j
public class ResilientRedisCache implements Cache {

    private static final Duration LOCK_TTL      = Duration.ofSeconds(5);
    private static final int      POLL_ATTEMPTS = 5;
    private static final long     POLL_DELAY_MS = 20;

    // Lua script for lock release — only delete if we still own it (compare
    // the lock's value to our token before deleting). Prevents the classic
    // bug where a slow request deletes a DIFFERENT request's lock after its
    // own lock already expired.
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class);

    private final Cache delegate;
    private final RedisCircuitBreaker circuitBreaker;
    private final StringRedisTemplate lockTemplate;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter putCounter;
    private final Counter evictCounter;
    private final Counter circuitSkipCounter;
    private final Timer   redisLatencyTimer;

    public ResilientRedisCache(Cache delegate, RedisCircuitBreaker circuitBreaker,
                               StringRedisTemplate lockTemplate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.lockTemplate = lockTemplate;
        String name = delegate.getName();
        this.hitCounter         = Counter.builder("kashigrc.cache.requests").tag("cache", name).tag("result", "hit").register(meterRegistry);
        this.missCounter        = Counter.builder("kashigrc.cache.requests").tag("cache", name).tag("result", "miss").register(meterRegistry);
        this.putCounter         = Counter.builder("kashigrc.cache.puts").tag("cache", name).register(meterRegistry);
        this.evictCounter       = Counter.builder("kashigrc.cache.evictions").tag("cache", name).register(meterRegistry);
        this.circuitSkipCounter = Counter.builder("kashigrc.cache.circuit_skips").tag("cache", name).register(meterRegistry);
        this.redisLatencyTimer  = Timer.builder("kashigrc.cache.redis_latency").tag("cache", name).register(meterRegistry);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        if (!circuitBreaker.allowRequest()) {
            circuitSkipCounter.increment();
            return null; // treated as a miss — caller's method runs normally
        }
        try {
            ValueWrapper result = redisLatencyTimer.record(() -> delegate.get(key));
            circuitBreaker.recordSuccess();
            (result != null ? hitCounter : missCounter).increment();
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[CACHE] {} GET failed, treating as miss | key={} — {}", getName(), key, e.toString());
            return null;
        }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        if (!circuitBreaker.allowRequest()) {
            circuitSkipCounter.increment();
            return null;
        }
        try {
            T result = redisLatencyTimer.record(() -> delegate.get(key, type));
            circuitBreaker.recordSuccess();
            (result != null ? hitCounter : missCounter).increment();
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[CACHE] {} GET failed, treating as miss | key={} — {}", getName(), key, e.toString());
            return null;
        }
    }

    /**
     * The stampede-protected path — this is what @Cacheable actually calls.
     * See class javadoc point 2 for the algorithm.
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper cached = circuitBreaker.allowRequest() ? safeGet(key) : null;
        if (cached != null) {
            @SuppressWarnings("unchecked")
            T value = (T) cached.get();
            return value;
        }
        if (!circuitBreaker.allowRequest()) {
            // Circuit open — no lock, no Redis, just load and return. Don't
            // even try to cache the result; Redis is presumed unhealthy.
            return loadDirect(valueLoader);
        }

        String lockKey = "lock:" + getName() + ":" + key;
        String token = UUID.randomUUID().toString();
        boolean acquired = tryLock(lockKey, token);

        if (acquired) {
            try {
                T value = loadDirect(valueLoader);
                put(key, value);
                return value;
            } finally {
                unlock(lockKey, token);
            }
        }

        // Someone else is repopulating this key — poll briefly instead of
        // also hitting the DB (this is the actual stampede protection: N
        // concurrent misses collapse into 1 DB load + N short cache polls).
        for (int i = 0; i < POLL_ATTEMPTS; i++) {
            try { Thread.sleep(POLL_DELAY_MS); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            ValueWrapper polled = safeGet(key);
            if (polled != null) {
                @SuppressWarnings("unchecked")
                T value = (T) polled.get();
                return value;
            }
        }
        // Lock holder didn't finish within our poll window (slow DB query,
        // or it died mid-flight) — never block forever, just load directly.
        // Worst case this duplicates one DB call, which is still far better
        // than every concurrent caller doing so.
        return loadDirect(valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        if (!circuitBreaker.allowRequest()) {
            circuitSkipCounter.increment();
            return;
        }
        try {
            redisLatencyTimer.record(() -> delegate.put(key, value));
            circuitBreaker.recordSuccess();
            putCounter.increment();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[CACHE] {} PUT failed, value not cached | key={} — {}", getName(), key, e.toString());
        }
    }

    @Override
    public void evict(Object key) {
        if (!circuitBreaker.allowRequest()) {
            circuitSkipCounter.increment();
            return;
        }
        try {
            redisLatencyTimer.record(() -> delegate.evict(key));
            circuitBreaker.recordSuccess();
            evictCounter.increment();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[CACHE] {} EVICT failed, stale entry may persist until TTL | key={} — {}",
                    getName(), key, e.toString());
        }
    }

    @Override
    public void clear() {
        if (!circuitBreaker.allowRequest()) {
            circuitSkipCounter.increment();
            return;
        }
        try {
            redisLatencyTimer.record(delegate::clear);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[CACHE] {} CLEAR failed, stale entries may persist until TTL — {}", getName(), e.toString());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ValueWrapper safeGet(Object key) {
        try {
            return delegate.get(key);
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return null;
        }
    }

    private <T> T loadDirect(Callable<T> valueLoader) {
        try {
            return valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(null, valueLoader, e);
        }
    }

    private boolean tryLock(String lockKey, String token) {
        try {
            Boolean acquired = lockTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Can't even reach Redis for the lock — treat as "didn't acquire"
            // so the caller falls through to loadDirect() rather than hanging.
            circuitBreaker.recordFailure();
            return false;
        }
    }

    private void unlock(String lockKey, String token) {
        try {
            lockTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (Exception e) {
            log.warn("[CACHE] Lock release failed for {} — it will expire on its own via TTL ({}s)",
                    lockKey, LOCK_TTL.getSeconds());
        }
    }
}