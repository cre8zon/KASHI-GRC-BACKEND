package com.kashi.grc.evidence.service;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.evidence.domain.EvidenceLink;
import com.kashi.grc.evidence.domain.EvidenceRecord;
import com.kashi.grc.integration.domain.TenantIntegrationCheck;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * KashiLink — read-side queries for the coverage page.
 *
 * JPA Criteria API throughout, matching the *RepositoryImpl convention used
 * across the codebase. The coverage census spans five entities that share no JPA
 * association, so rather than a native UNION this runs one small grouped query
 * per source and merges in Java. Slightly more code; stays in convention, stays
 * type-checked, and each query is independently readable.
 *
 * Every query is tenant-parameterised.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KashiLinkQueryService {

    @PersistenceContext
    private EntityManager em;

    private final AuditEngagementRepository engagementRepository;

    /** Which link statuses count as a live link. */
    private static final List<EvidenceLink.Status> LIVE_STATUSES = List.of(
            EvidenceLink.Status.PENDING_REVIEW,
            EvidenceLink.Status.ACCEPTED,
            EvidenceLink.Status.AUTOMATION_VERIFIED
    );

    // ── Overview ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> stats(Long tenantId) {

        long records = countRecords(tenantId, false);
        long links   = countLinks(tenantId, LIVE_STATUSES, null);
        long pending = countLinks(tenantId, List.of(EvidenceLink.Status.PENDING_REVIEW), Boolean.TRUE);
        long orphans = countRecords(tenantId, true);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidenceRecords",  records);
        out.put("evidenceLinks",    links);
        out.put("reuseRatio",       records == 0 ? 0.0
                : Math.round((double) links / records * 100) / 100.0);
        out.put("pendingReview",    pending);
        out.put("orphanEvidence",   orphans);
        out.put("untaggedControls", countUntagged(AuditControlInstance.class, "controlTagSnapshot", tenantId));
        out.put("untaggedTests",    countUntagged(AuditTestInstance.class, "controlTagSnapshot", tenantId));
        return out;
    }

    private long countRecords(Long tenantId, boolean orphansOnly) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EvidenceRecord> r = cq.from(EvidenceRecord.class);

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(r.get("tenantId"), tenantId));
        where.add(cb.isFalse(r.get("expired")));
        if (orphansOnly) {
            where.add(cb.isNotNull(r.get("controlTag")));
            where.add(cb.equal(r.get("linkCount"), 0));
        }
        cq.select(cb.count(r)).where(where.toArray(new Predicate[0]));
        return em.createQuery(cq).getSingleResult();
    }

    private long countLinks(Long tenantId, List<EvidenceLink.Status> statuses, Boolean autoLinked) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EvidenceLink> l = cq.from(EvidenceLink.class);

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(l.get("tenantId"), tenantId));
        where.add(l.get("status").in(statuses));
        if (autoLinked != null) where.add(cb.equal(l.get("autoLinked"), autoLinked));

        cq.select(cb.count(l)).where(where.toArray(new Predicate[0]));
        return em.createQuery(cq).getSingleResult();
    }

    private <T> long countUntagged(Class<T> entity, String tagField, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<T> e = cq.from(entity);
        cq.select(cb.count(e)).where(
                cb.equal(e.get("tenantId"), tenantId),
                cb.or(cb.isNull(e.get(tagField)), cb.equal(e.get(tagField), ""))
        );
        return em.createQuery(cq).getSingleResult();
    }

    // ── Per-tag coverage ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> coverage(Long tenantId) {

        Map<String, Long> controlInstances = tagCounts(AuditControlInstance.class, "controlTagSnapshot", tenantId);
        Map<String, Long> testInstances    = tagCounts(AuditTestInstance.class,    "controlTagSnapshot", tenantId);
        Map<String, Long> evidenceRecords  = tagCounts(EvidenceRecord.class,       "controlTag",         tenantId);
        Map<String, Long> integrations     = tagCounts(TenantIntegrationCheck.class, "controlTag",       tenantId);
        Map<String, Long> policyInstances  = policyTagCounts(tenantId);

        Set<String> allTags = new TreeSet<>();
        allTags.addAll(controlInstances.keySet());
        allTags.addAll(testInstances.keySet());
        allTags.addAll(evidenceRecords.keySet());
        allTags.addAll(integrations.keySet());
        allTags.addAll(policyInstances.keySet());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String tag : allTags) {
            long ctrl     = controlInstances.getOrDefault(tag, 0L);
            long test     = testInstances.getOrDefault(tag, 0L);
            long policy   = policyInstances.getOrDefault(tag, 0L);
            long evidence = evidenceRecords.getOrDefault(tag, 0L);
            long reachable = ctrl + test + policy;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag",               tag);
            m.put("controlInstances",  ctrl);
            m.put("testInstances",     test);
            m.put("policyInstances",   policy);
            m.put("evidenceRecords",   evidence);
            m.put("integrationChecks", integrations.getOrDefault(tag, 0L));
            m.put("reachable",         reachable);
            // DRIFT   evidence filed but nothing carries the tag — a typo, or a
            //         tag renamed in the library after the evidence was uploaded
            // UNUSED  instances carry it but no evidence exists yet
            // OK      both sides present
            m.put("health", reachable == 0 && evidence > 0 ? "DRIFT"
                    : reachable > 0 && evidence == 0 ? "UNUSED"
                      : reachable == 0 ? "EMPTY" : "OK");
            out.add(m);
        }
        return out;
    }

    /** GROUP BY tag, COUNT(*) for any tenant-scoped entity with a single-tag column. */
    private <T> Map<String, Long> tagCounts(Class<T> entity, String tagField, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<T> e = cq.from(entity);

        cq.multiselect(e.get(tagField), cb.count(e))
                .where(
                        cb.equal(e.get("tenantId"), tenantId),
                        cb.isNotNull(e.get(tagField)),
                        cb.notEqual(e.get(tagField), "")
                )
                .groupBy(e.get(tagField));

        Map<String, Long> out = new HashMap<>();
        for (Object[] row : em.createQuery(cq).getResultList()) {
            out.merge(((String) row[0]).toUpperCase().trim(), (Long) row[1], Long::sum);
        }
        return out;
    }

    /**
     * Policy instances hold a CSV tag list, so the split has to happen in Java —
     * SQL GROUP BY cannot decompose it. Only the tag column is selected, never
     * whole entities.
     */
    private Map<String, Long> policyTagCounts(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<AuditPolicyInstance> p = cq.from(AuditPolicyInstance.class);

        cq.select(p.get("controlTagsSnapshot")).where(
                cb.equal(p.get("tenantId"), tenantId),
                cb.isNotNull(p.get("controlTagsSnapshot")),
                cb.notEqual(p.get("controlTagsSnapshot"), "")
        );

        Map<String, Long> out = new HashMap<>();
        for (String csv : em.createQuery(cq).getResultList()) {
            for (String raw : csv.split(",")) {
                String tag = raw.toUpperCase().trim();
                if (!tag.isEmpty()) out.merge(tag, 1L, Long::sum);
            }
        }
        return out;
    }

    // ── Gaps ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> gaps(Long tenantId) {
        return Map.of(
                "engagementsWithUntaggedControls", untaggedByEngagement(tenantId),
                "orphanEvidence",                  orphanEvidence(tenantId));
    }

    /**
     * Per-engagement totals vs untagged counts. Two grouped queries merged in
     * Java rather than one query with a conditional aggregate — Criteria can
     * express the latter, but it reads far worse than this does.
     */
    private List<Map<String, Object>> untaggedByEngagement(Long tenantId) {
        Map<Long, Long> totals   = engagementControlCounts(tenantId, false);
        Map<Long, Long> untagged = engagementControlCounts(tenantId, true);
        if (untagged.isEmpty()) return List.of();

        Map<Long, AuditEngagement> engagements = new HashMap<>();
        engagementRepository.findAllById(untagged.keySet())
                .forEach(e -> engagements.put(e.getId(), e));

        List<Map<String, Object>> out = new ArrayList<>();
        untagged.forEach((engagementId, count) -> {
            AuditEngagement e = engagements.get(engagementId);
            if (e == null || !tenantId.equals(e.getTenantId())) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engagementId", engagementId);
            m.put("name",         e.getName());
            m.put("frameworkRef", e.getFrameworkRef());
            m.put("untagged",     count);
            m.put("total",        totals.getOrDefault(engagementId, count));
            out.add(m);
        });
        out.sort((a, b) -> Long.compare((Long) b.get("untagged"), (Long) a.get("untagged")));
        return out;
    }

    private Map<Long, Long> engagementControlCounts(Long tenantId, boolean untaggedOnly) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(c.get("tenantId"), tenantId));
        if (untaggedOnly) {
            where.add(cb.or(cb.isNull(c.get("controlTagSnapshot")),
                    cb.equal(c.get("controlTagSnapshot"), "")));
        }

        cq.multiselect(c.get("engagementId"), cb.count(c))
                .where(where.toArray(new Predicate[0]))
                .groupBy(c.get("engagementId"));

        Map<Long, Long> out = new LinkedHashMap<>();
        for (Object[] row : em.createQuery(cq).getResultList()) {
            if (row[0] != null) out.put((Long) row[0], (Long) row[1]);
        }
        return out;
    }

    private List<Map<String, Object>> orphanEvidence(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<EvidenceRecord> cq = cb.createQuery(EvidenceRecord.class);
        Root<EvidenceRecord> r = cq.from(EvidenceRecord.class);

        cq.where(
                cb.equal(r.get("tenantId"), tenantId),
                cb.isNotNull(r.get("controlTag")),
                cb.equal(r.get("linkCount"), 0),
                cb.isFalse(r.get("expired"))
        );
        cq.orderBy(cb.desc(r.get("uploadedAt")));

        List<Map<String, Object>> out = new ArrayList<>();
        for (EvidenceRecord rec : em.createQuery(cq).setMaxResults(200).getResultList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",             rec.getId());
            m.put("title",          rec.getTitle());
            m.put("controlTag",     rec.getControlTag());
            m.put("collectionType", rec.getCollectionType() != null
                    ? rec.getCollectionType().name() : "MANUAL");
            m.put("uploadedAt",     rec.getUploadedAt());
            out.add(m);
        }
        return out;
    }
}