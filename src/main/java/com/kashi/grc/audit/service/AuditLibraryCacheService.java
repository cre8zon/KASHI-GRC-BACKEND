package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.repository.AuditPolicyRepository;
import com.kashi.grc.audit.repository.AuditTestRepository;
import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cached read models for the policy and test library lists.
 *
 * ── WHY A SEPARATE BEAN ─────────────────────────────────────────────────────
 * Spring's cache advice is applied by a proxy, so a @Cacheable method called
 * from inside the same class bypasses it entirely. The controllers build these
 * lists themselves, so caching had to move to a bean they call across.
 *
 * ── WHY MAPS AND NOT THE SUMMARY RECORDS ────────────────────────────────────
 * The Redis serializer runs with DefaultTyping.NON_FINAL. Records are final
 * classes, so Jackson writes no @class marker for them and they come back out
 * of Redis as LinkedHashMap — a ClassCastException at the first field access.
 * Maps sidestep that, and they are what the endpoints return anyway, so nothing
 * is converted twice. (PaginatedResponse hit the neighbouring version of this
 * problem: final FIELDS with no creator, silently failing every cache read.)
 *
 * ── WHAT IS DELIBERATELY NOT CACHED ─────────────────────────────────────────
 * `editable`. It is derived from utilityService.isSystemUser(), which is a
 * property of the USER, while TenantAwareKeyGenerator keys per TENANT. Caching
 * it would let one user's answer be served to another in the same tenant —
 * a platform admin's `editable: true` reaching an org user is exactly the
 * confusion the flag exists to prevent. `origin` IS cached: it derives from the
 * row's tenant_id, which does not vary by caller.
 *
 * Callers add `editable` after reading. See AuditPolicyController.listPolicies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLibraryCacheService {

    private final AuditPolicyRepository policyRepository;
    private final AuditTestRepository   testRepository;
    private final UserRepository        userRepository;

    /**
     * Dates as ISO strings, never as temporal objects.
     *
     * The Redis serializer runs with DefaultTyping.NON_FINAL. A LocalDate inside
     * a Map<String,Object> is written with a type id the reader cannot match back
     * to the declared Object, so EVERY cached read failed:
     *
     *   SerializationException: Unexpected token (START_OBJECT), expected
     *   VALUE_STRING ... that contains type id (for subtype of java.lang.Object)
     *
     * ResilientRedisCache swallows that as a miss, so the cache appeared to work
     * while never returning anything — which is why these lists still cost a full
     * database round trip on every request.
     */
    private static Object iso(Object temporal) {
        return temporal == null ? null : temporal.toString();
    }

    // ── Policies ────────────────────────────────────────────────────────────

    /**
     * @param status raw string, parsed here — keeping the cache key primitive
     *               avoids an enum in the key, which the serializer would have
     *               to round-trip for no benefit.
     */
    @Cacheable(cacheNames = CacheNames.AUDIT_POLICY_LIST)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> policyList(Long tenantId, String search,
                                                String status, String origin) {
        AuditPolicy.PolicyStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = AuditPolicy.PolicyStatus.valueOf(status);
            } catch (IllegalArgumentException ignored) {
                return List.of();   // unknown status from a stale bookmark
            }
        }

        log.debug("[LIBRARY-CACHE] MISS policyList tenantId={} search={} status={} origin={}",
                tenantId, search, status, origin);

        var rows = policyRepository.findSummariesForTenant(tenantId, search, statusFilter, origin);

        // Owner names in ONE query, not one per row.
        //
        // The OWNER column renders ownerTeam, which is null on nearly every
        // adopted policy, so it showed "—" while the document itself read
        // "Owner: <name>" from ownerId. Two different fields; a policy that has
        // an owner should not display as unowned.
        java.util.Map<Long, String> ownerNames = new java.util.HashMap<>();
        var ownerIds = rows.stream().map(p -> p.ownerId())
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!ownerIds.isEmpty()) {
            userRepository.findAllById(ownerIds)
                    .forEach(u -> ownerNames.put(u.getId(), u.getFullName()));
        }

        return rows.stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",                    p.id());
                    m.put("title",                 p.title());
                    m.put("policyRef",             p.policyRef());
                    m.put("description",           p.description());
                    m.put("version",               p.version());
                    m.put("status",                p.status()      != null ? p.status().name()      : null);
                    m.put("contentType",           p.contentType() != null ? p.contentType().name() : null);
                    m.put("ownerId",               p.ownerId());
                    m.put("ownerTeam",             p.ownerTeam());
                    // What the OWNER column shows: the team when set, otherwise
                    // the accountable person.
                    m.put("ownerName", p.ownerTeam() != null && !p.ownerTeam().isBlank()
                            ? p.ownerTeam()
                            : ownerNames.get(p.ownerId()));
                    m.put("approvedAt",            iso(p.approvedAt()));
                    m.put("effectiveDate",         iso(p.effectiveDate()));
                    m.put("nextReviewDate",        iso(p.nextReviewDate()));
                    m.put("reviewFrequencyMonths", p.reviewFrequencyMonths());
                    m.put("controlTags",           p.controlTags());
                    m.put("frameworkRefs",         p.frameworkRefs());
                    m.put("tenantId",              p.tenantId());
                    m.put("createdAt",             iso(p.createdAt()));
                    m.put("origin",                p.tenantId() == null ? "GLOBAL" : "ORG");
                    return m;
                })
                .toList();
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Cacheable(cacheNames = CacheNames.AUDIT_TEST_LIST)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> testList(Long tenantId, String search) {
        log.debug("[LIBRARY-CACHE] MISS testList tenantId={} search={}", tenantId, search);

        return testRepository.findSummariesForTenant(tenantId, search)
                .stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",              t.id());
                    m.put("name",            t.name());
                    m.put("testRef",         t.testRef());
                    m.put("description",     t.description());
                    m.put("frameworkRef",    t.frameworkRef());
                    m.put("frameworkTestId", t.frameworkTestId());
                    m.put("controlTag",      t.controlTag());
                    m.put("automationType",  t.automationType() != null ? t.automationType().name() : null);
                    m.put("automationKey",   t.automationKey());
                    m.put("frequency",       t.frequency()      != null ? t.frequency().name()      : null);
                    m.put("tenantId",        t.tenantId());
                    m.put("createdAt",       iso(t.createdAt()));
                    m.put("origin",          t.tenantId() == null ? "GLOBAL" : "ORG");
                    return m;
                })
                .toList();
    }

    // ── Eviction ────────────────────────────────────────────────────────────
    //
    // allEntries, not a keyed evict. The cache key includes search, status and
    // origin, so a single policy edit invalidates an unknowable number of
    // filtered permutations. Clearing the whole namespace is the only correct
    // answer, and these lists are cheap to rebuild.
    //
    // Both lists are evicted together: linking a policy to a control, or a CSV
    // import, can change either side, and reasoning about which is a bug
    // waiting to happen.

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.AUDIT_POLICY_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.AUDIT_TEST_LIST,   allEntries = true),
    })
    public void evictLibraryLists() {
        log.debug("[LIBRARY-CACHE] Evicted policy + test lists");
    }
}