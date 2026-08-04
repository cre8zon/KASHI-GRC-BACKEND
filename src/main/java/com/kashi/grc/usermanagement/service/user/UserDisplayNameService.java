package com.kashi.grc.usermanagement.service.user;

import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves userId -> display name for history, assignment, and audit-trail
 * screens.
 *
 * WHY THIS EXISTS: WorkflowEngineService.toHistoryResponse (and similar
 * mappers elsewhere) used to call userRepository.findById() once PER HISTORY
 * ROW — a 200-entry history page meant 200 remote round trips just to show
 * names. This class fixes it at two layers, same as the getForm() fix:
 *   1. STRUCTURAL: resolveNames() takes the full set of IDs a caller needs
 *      and issues ONE findAllById() query for whatever isn't already cached
 *      — never N individual finds.
 *   2. CACHE: results are kept in Redis (tenant-scoped key) so the *next*
 *      history page for the same tenant costs zero DB round trips for names
 *      that already resolved, even after this request ends.
 *
 * Redis-optional by design: if kashi.redis.enabled=false (or Redis is
 * unreachable), cacheManagerProvider.getIfAvailable() returns null and this
 * degrades to "structural fix only" — still one batched query instead of N,
 * just no cross-request memoization. Never throws either way, mirroring
 * KafkaEventPublisher's contract: a caching/Redis problem must never surface
 * as a broken history screen.
 */
@Slf4j
@Service
public class UserDisplayNameService {

    private final UserRepository userRepository;
    private final ObjectProvider<CacheManager> cacheManagerProvider;

    public UserDisplayNameService(UserRepository userRepository,
                                  ObjectProvider<CacheManager> cacheManagerProvider) {
        this.userRepository = userRepository;
        this.cacheManagerProvider = cacheManagerProvider;
    }

    /** Single-id convenience wrapper around resolveNames(). */
    public String resolveName(Long userId) {
        if (userId == null) return null;
        return resolveNames(List.of(userId)).get(userId);
    }

    /**
     * Resolves every id in one pass: cache hits are free, cache misses are
     * fetched in exactly one findAllById() call (not one per miss), then
     * written back to cache for next time.
     */
    public Map<Long, String> resolveNames(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();

        Cache cache = getCache();
        Map<Long, String> resolved = new HashMap<>();
        Set<Long> misses = new HashSet<>();

        for (Long id : ids) {
            String cached = cacheGet(cache, id);
            if (cached != null) {
                resolved.put(id, cached);
            } else {
                misses.add(id);
            }
        }

        if (!misses.isEmpty()) {
            Map<Long, String> fromDb = userRepository.findAllById(misses).stream()
                    .collect(Collectors.toMap(User::getId, this::displayName));
            for (Long id : misses) {
                String name = fromDb.getOrDefault(id, "User #" + id);
                resolved.put(id, name);
                cachePut(cache, id, name);
            }
        }
        return resolved;
    }

    // ── Cache plumbing — manual get/put (not @Cacheable) because we need to
    // batch-check/batch-populate a collection in one pass; annotation-driven
    // caching only covers whole-method-call granularity. Since this bypasses
    // the AOP proxy, LoggingCacheErrorHandler doesn't apply here — these
    // try/catch blocks are what keep the "never throws" contract intact for
    // the manual path. ────────────────────────────────────────────────────

    private Cache getCache() {
        CacheManager manager = cacheManagerProvider.getIfAvailable();
        if (manager == null) return null;
        return manager.getCache(CacheNames.USER_DISPLAY_NAME);
    }

    private String cacheGet(Cache cache, Long userId) {
        if (cache == null) return null;
        try {
            return cache.get(cacheKey(userId), String.class);
        } catch (Exception e) {
            log.warn("[USER-DISPLAY-NAME] cache GET failed for userId={}, falling through to DB — {}",
                    userId, e.toString());
            return null;
        }
    }

    private void cachePut(Cache cache, Long userId, String name) {
        if (cache == null) return;
        try {
            cache.put(cacheKey(userId), name);
        } catch (Exception e) {
            log.warn("[USER-DISPLAY-NAME] cache PUT failed for userId={}, name not cached — {}",
                    userId, e.toString());
        }
    }

    private String cacheKey(Long userId) {
        Long tenantId = TenantContext.getCurrentTenant();
        return (tenantId != null ? tenantId : "global") + ":user:" + userId;
    }

    private String displayName(User u) {
        String name = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return name.isEmpty() ? u.getEmail() : name;
    }
}