package com.kashi.grc.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;

/**
 * CONTRACT: a cache operation NEVER throws — mirrors KafkaEventPublisher's
 * "publish() never throws" rule. Redis is an accelerator, not a dependency;
 * if it's down, slow, or misconfigured, every {@code @Cacheable}/
 * {@code @CacheEvict}/{@code @CachePut} call falls straight through to the
 * annotated method (i.e. behaves exactly like caching was never added) with
 * a logged WARN, instead of turning a Redis blip into a 500 on every screen
 * in the app.
 *
 * Without this, Spring's default CacheErrorHandler RETHROWS get/put/evict
 * failures — meaning an unreachable Redis would take the entire app down
 * with it. This one class is what makes that impossible.
 */
@Slf4j
@Component
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("[CACHE] GET failed | cache={} | key={} | falling through to source — {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("[CACHE] PUT failed | cache={} | key={} | value not cached, next read will re-fetch — {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("[CACHE] EVICT failed | cache={} | key={} | stale entry may persist until TTL — {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("[CACHE] CLEAR failed | cache={} | stale entries may persist until TTL — {}",
                cache.getName(), exception.toString());
    }
}