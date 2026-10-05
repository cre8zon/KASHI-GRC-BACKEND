package com.kashi.grc.assessment.service;

import com.kashi.grc.common.cache.CacheNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Progress for a long operation, readable while it is still running.
 *
 * ── WHY A SIDE CHANNEL IS NECESSARY AT ALL ────────────────────────────────
 * executeAssessment snapshots an entire template — sections, questions,
 * options — inside one @Transactional method. That is correct: a half-created
 * assessment is worse than none, so it has to be all or nothing.
 *
 * But it also means nothing it writes is visible until it commits. Polling the
 * assessment row tells you nothing, because the row does not exist yet. That is
 * the actual reason users "go blank on what's happening" — not Kafka, and not
 * the duration. Any amount of progress written inside that transaction is
 * invisible for exactly as long as the user needs it.
 *
 * So progress goes somewhere outside the transaction.
 *
 * ── WHY THE EXISTING CACHE AND NOT A NEW TABLE ────────────────────────────
 * A table would need a migration, a cleanup job for rows nobody reads after
 * sixty seconds, and a decision about retention for data with no audit value.
 *
 * The platform already runs Redis behind CacheManager. Writes there are outside
 * the JPA transaction by construction, and visible to every instance — so a
 * poll that lands on a different node than the one doing the work still sees
 * the truth. That is the property an in-memory map would not have, and it is
 * the one that matters the moment there is more than one instance.
 *
 * ── AND WHY LOSING IT IS ACCEPTABLE ───────────────────────────────────────
 * If the process dies mid-snapshot the transaction rolls back and the work is
 * gone too. Progress that vanishes alongside the thing it was describing is
 * consistent; it never survives to describe something that did not happen.
 *
 * The same holds for a Redis outage. ResilientRedisCache opens its circuit and
 * every get returns a miss, so the snapshot still runs and the UI falls back to
 * a spinner. Progress is a convenience and is built to fail like one.
 *
 * ── THE VALUE IS A MAP, NOT A RECORD, AND THAT IS NOT A STYLE CHOICE ──────
 * The first version of this class cached a Progress record. It would have
 * failed on every single read.
 *
 * CacheConfig's serializer runs activateDefaultTyping(..., NON_FINAL, ...).
 * Records are final, so Jackson writes no @class marker, and the value comes
 * back as a LinkedHashMap that cannot be cast to the record type. CacheNames
 * documents this against AUDIT_POLICY_LIST, where it was found the hard way:
 *
 *     "Cached values are List<Map<String,Object>> deliberately, NOT the summary
 *      records: ... records are final, so no @class marker is written and they
 *      would deserialise as LinkedHashMap and fail on cast."
 *
 * A LinkedHashMap of scalars round-trips through that serializer unchanged, and
 * it is also exactly the shape the controller returns — so nothing is converted
 * twice on the way out either.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentProgressTracker {

    private final CacheManager cacheManager;

    // Keys inside the stored map. Package-visible constants rather than loose
    // strings, because the read side and the write side must agree and a typo
    // here degrades silently to "known: false" rather than failing.
    private static final String K_PHASE    = "phase";
    private static final String K_DONE     = "done";
    private static final String K_TOTAL    = "total";
    private static final String K_PERCENT  = "percent";
    private static final String K_DETAIL   = "detail";
    private static final String K_FINISHED = "finished";

    // ═════════════════════════════════════════════════════════════════════════
    // WRITE
    // ═════════════════════════════════════════════════════════════════════════

    public void start(String key, String phase, int total) {
        write(key, build(phase, 0, total, null, false));
    }

    public void step(String key, String phase, int done, int total, String detail) {
        write(key, build(phase, done, total, detail, false));
    }

    /**
     * Marks it done.
     *
     * Deliberately still written rather than cleared: a client that polls once
     * more after the call returns should see "finished", not an empty response
     * it has to interpret. An absent key is ambiguous — never started, already
     * expired, or wrong key — and the UI would have to guess which.
     */
    public void finish(String key, String phase, int total) {
        write(key, build(phase, total, total, null, true));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * For a controller to return directly.
     *
     * Always answers. "known: false" is a real answer — it says the server has
     * nothing on this key, without claiming to know whether that is because the
     * work has not started, already finished and expired, or the key is wrong.
     * The caller cannot distinguish those from here, so this does not pretend to.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap(String key) {
        Map<String, Object> out = new LinkedHashMap<>();

        Cache c = cacheManager.getCache(CacheNames.ASSESSMENT_PROGRESS);
        Object raw = (c == null) ? null : c.get(key, Map.class);
        if (raw == null) {
            out.put("known", false);
            return out;
        }

        out.put("known", true);
        out.putAll((Map<String, Object>) raw);
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNALS
    // ═════════════════════════════════════════════════════════════════════════

    private Map<String, Object> build(String phase, int done, int total,
                                      String detail, boolean finished) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(K_PHASE,    phase);
        m.put(K_DONE,     done);
        m.put(K_TOTAL,    total);
        // Computed on write, not on read. The client should not have to do
        // arithmetic to render a bar, and a divide-by-zero on an empty template
        // should happen once here rather than in every consumer.
        m.put(K_PERCENT,  total <= 0 ? 0 : Math.min(100, (int) Math.round(done * 100.0 / total)));
        m.put(K_DETAIL,   detail);
        m.put(K_FINISHED, finished);
        return m;
    }

    private void write(String key, Map<String, Object> value) {
        Cache c = cacheManager.getCache(CacheNames.ASSESSMENT_PROGRESS);
        if (c == null) {
            // Caching disabled (kashi.redis.enabled=false) or the region is not
            // registered. Progress is a convenience, never a correctness
            // requirement — the snapshot runs regardless and the UI falls back
            // to a spinner. Logged at debug rather than warn, because a hundred
            // warnings during one snapshot is its own problem.
            log.debug("[ASSESSMENT-PROGRESS] cache '{}' unavailable, progress not reported",
                    CacheNames.ASSESSMENT_PROGRESS);
            return;
        }
        c.put(key, value);
    }

    /**
     * The cache key.
     *
     * Tenant-scoped explicitly. TenantAwareKeyGenerator only applies to keys
     * Spring generates for @Cacheable methods; a direct cache.put chooses its
     * own key and gets no such help. Task ids are globally unique so a
     * collision is not actually reachable today, but a cache key that carries
     * its tenant cannot start leaking across tenants later because somebody
     * changed how ids are allocated.
     */
    public static String keyFor(Long tenantId, Long taskId) {
        return "t" + tenantId + ":task:" + taskId;
    }
}
