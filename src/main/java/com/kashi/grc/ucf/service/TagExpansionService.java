package com.kashi.grc.ucf.service;

import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.ucf.domain.CommonControl;
import com.kashi.grc.ucf.repository.CommonControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 3 — expands a control tag into the frozen set that instantiation writes
 * into matched_tags_snapshot.
 *
 * "IAM-02.3"  ->  "IAM-02.3,IAM-02,IAM"
 *
 * Ancestors only, never descendants. A control sitting on a coarse node must
 * not be satisfied by evidence for one narrow child of it. This is the whole
 * point of the hierarchy: coarse evidence reaches fine controls, not the reverse.
 *
 * ── WHY A CACHED SNAPSHOT ───────────────────────────────────────────────────
 * Instantiation resolves a chain per control instance, and an engagement can
 * have hundreds. Hitting the DB per control would be a query storm. The
 * catalogue is a few hundred effectively-static rows, so it is loaded once into
 * an in-memory parent map and refreshed lazily. If the catalogue is edited, the
 * next instantiation after the TTL picks it up — and since snapshots freeze at
 * instantiation anyway, a slightly stale map only ever affects brand-new
 * expansions, never existing ones.
 *
 * ── LEGACY FALLBACK ─────────────────────────────────────────────────────────
 * If the tag is not a catalogue code (an old free-text tag on an un-migrated
 * control), expand() returns just the tag itself. The upgraded matcher treats a
 * single-element set exactly as the old exact-match did, so nothing regresses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagExpansionService {

    private final CommonControlRepository controlRepository;
    private final ObjectProvider<CacheManager> cacheManagerProvider;

    private static final long TTL_MS = 5 * 60_000;

    // Redis L2 — one fixed key, not tenant-scoped: the UCF catalogue is global,
    // shared by every tenant (see CacheNames.UCF_CATALOGUE javadoc).
    private static final String REDIS_KEY = "parentMap";

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    private record Snapshot(Map<String, String> parentByCode, long loadedAt) {}

    /**
     * Expand one tag to its comma-joined ancestry chain, uppercased.
     * Returns the tag alone if it is not a known catalogue code.
     */
    public String expand(String tag) {
        if (tag == null || tag.isBlank()) return null;
        String code = tag.toUpperCase().trim();

        Map<String, String> parents = parentMap();
        if (!parents.containsKey(code)) {
            return code;   // legacy tag — single-element set, matches as before
        }

        List<String> chain = new ArrayList<>();
        String cursor = code;
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            chain.add(cursor);
            cursor = parents.get(cursor);
        }
        return String.join(",", chain);
    }

    /**
     * Expand a CSV of tags (policy control_tags) into the DISTINCT union of all
     * their chains, order-preserved.
     *
     * "GOV-01.1,AST-02.1"  ->  "GOV-01.1,GOV-01,GOV,AST-02.1,AST-02,AST"
     */
    public String expandCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> all = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String expanded = expand(raw);
            if (expanded != null) {
                for (String c : expanded.split(",")) all.add(c);
            }
        }
        return all.isEmpty() ? null : String.join(",", all);
    }

    // ── cache ───────────────────────────────────────────────────────────────

    private Map<String, String> parentMap() {
        Snapshot s = cache.get();
        if (s == null || System.currentTimeMillis() - s.loadedAt() > TTL_MS) {
            s = load();
            cache.set(s);
        }
        return s.parentByCode();
    }

    @SuppressWarnings("unchecked")
    private Snapshot load() {
        Cache cache = getRedisCache();

        if (cache != null) {
            try {
                Map<String, String> fromRedis = cache.get(REDIS_KEY, Map.class);
                if (fromRedis != null) {
                    log.debug("[UCF] Loaded {} catalogue codes from Redis L2 (skipped MySQL)", fromRedis.size());
                    return new Snapshot(fromRedis, System.currentTimeMillis());
                }
            } catch (Exception e) {
                log.warn("[UCF] Redis L2 read failed, falling through to MySQL — {}", e.toString());
            }
        }

        Map<String, String> map = new java.util.HashMap<>();
        for (CommonControl c : controlRepository.findAll()) {
            map.put(c.getCode(), c.getParentCode());
        }
        log.debug("[UCF] Loaded {} catalogue codes from MySQL into expansion cache", map.size());

        if (cache != null) {
            try {
                cache.put(REDIS_KEY, map);
            } catch (Exception e) {
                log.warn("[UCF] Redis L2 write failed, next reload (any instance) will re-hit MySQL — {}", e.toString());
            }
        }
        return new Snapshot(map, System.currentTimeMillis());
    }

    /**
     * Force a reload — call after a catalogue edit if immediacy is needed.
     * Clears both L1 (this instance's AtomicReference) and L2 (Redis, so
     * every other app instance also re-hits MySQL on its next expand() call
     * instead of serving the old map until its own TTL expires).
     */
    public void invalidate() {
        cache.set(null);
        Cache redisCache = getRedisCache();
        if (redisCache != null) {
            try {
                redisCache.evict(REDIS_KEY);
            } catch (Exception e) {
                log.warn("[UCF] Redis L2 evict failed, stale catalogue may persist on other instances until TTL — {}",
                        e.toString());
            }
        }
    }

    /**
     * Forces an L1+L2 load right now instead of waiting for the first real
     * expand() call to trigger it lazily. Called by CacheWarmupRunner on
     * app startup so the very first assessment/audit instantiation after a
     * deploy doesn't pay the cold-cache MySQL cost — global/tenant-independent
     * data, so this is always safe to warm unconditionally (see
     * CacheWarmupRunner for why tenant-scoped caches are NOT warmed the
     * same way).
     */
    public void warmUp() {
        int size = parentMap().size();
        log.info("[UCF] Cache warmed | codes={}", size);
    }

    private Cache getRedisCache() {
        CacheManager manager = cacheManagerProvider.getIfAvailable();
        return manager == null ? null : manager.getCache(CacheNames.UCF_CATALOGUE);
    }
}