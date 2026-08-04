package com.kashi.grc.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorates the real RedisCacheManager so every {@code @Cacheable} in the app
 * transparently gets circuit breaking, stampede protection, and metrics (see
 * ResilientRedisCache) without any call site changing. This is the bean
 * actually registered as the app's CacheManager — see CacheConfig.
 */
@RequiredArgsConstructor
public class ResilientCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final RedisCircuitBreaker circuitBreaker;
    private final StringRedisTemplate lockTemplate;
    private final MeterRegistry meterRegistry;

    // Wrapper instances are built once per cache name and reused — avoids
    // re-registering the same Micrometer meters on every getCache() call.
    private final Map<String, Cache> wrapped = new ConcurrentHashMap<>();

    @Override
    public Cache getCache(String name) {
        return wrapped.computeIfAbsent(name, n -> {
            Cache real = delegate.getCache(n);
            return real == null ? null : new ResilientRedisCache(real, circuitBreaker, lockTemplate, meterRegistry);
        });
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }
}