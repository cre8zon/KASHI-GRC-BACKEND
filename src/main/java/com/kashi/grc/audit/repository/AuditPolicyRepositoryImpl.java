package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.util.List;

/**
 * JPA Criteria API implementation of AuditPolicyRepositoryCustom.
 * Enum literal 'APPROVED' → AuditPolicy.PolicyStatus.APPROVED (type-safe).
 */
public class AuditPolicyRepositoryImpl implements AuditPolicyRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    /**
     * A tenant sees ALL of their own policies, but only PUBLISHED platform ones.
     *
     * A global policy in DRAFT or UNDER_REVIEW is the platform team working — not
     * something a client should browse, adopt, or map a control to. DEPRECATED is
     * withdrawn. Only APPROVED is an offer.
     *
     * Their own drafts are unaffected: the tenantId branch has no status filter,
     * because a tenant obviously needs to see the policy they are drafting.
     *
     * Platform admins are not filtered here — they read through
     * findByControlId/findAll paths that do not use this predicate, so the
     * library remains fully visible to whoever maintains it.
     */
    private Predicate visibleToTenant(CriteriaBuilder cb, Root<AuditPolicy> p, Long tenantId) {
        return visibleToTenant(cb, p, tenantId, false);
    }

    /**
     * A tenant sees their own rows plus APPROVED globals. A DRAFT global is the
     * platform team working and must not appear in a client library.
     *
     * systemUser lifts the APPROVED restriction, and without it the platform
     * cannot author at all: a newly created global policy is DRAFT, so the author
     * could not see the thing they had just written — and could not approve what
     * they could not open. The rule was written for tenants and then applied to
     * the people who produce the content it governs.
     *
     * It only became reachable once creation started deriving tenant_id = NULL
     * for platform admins; before that they wrote into their own tenant and the
     * ownRows branch covered them.
     */
    private Predicate visibleToTenant(CriteriaBuilder cb, Root<AuditPolicy> p,
                                      Long tenantId, boolean systemUser) {
        Predicate ownRows = cb.equal(p.get("tenantId"), tenantId);
        Predicate globals = systemUser
                ? cb.isNull(p.get("tenantId"))
                : cb.and(cb.isNull(p.get("tenantId")),
                cb.equal(p.get("status"), AuditPolicy.PolicyStatus.APPROVED));
        return cb.or(ownRows, globals);
    }

    @Override
    public long countForTenant(Long tenantId, boolean systemUser) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.select(cb.count(p)).where(visibleToTenant(cb, p, tenantId, systemUser));
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<AuditPolicySummary> findSummariesForTenant(Long tenantId, String search,
                                                           AuditPolicy.PolicyStatus status,
                                                           String origin, boolean systemUser) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicySummary> cq = cb.createQuery(AuditPolicySummary.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);

        // cb.construct, not cq.from(...).alias(...) — this is what keeps content_body
        // out of the generated SELECT. The column order must match the record.
        cq.select(cb.construct(AuditPolicySummary.class,
                p.get("id"), p.get("title"), p.get("policyRef"), p.get("description"),
                p.get("version"), p.get("status"), p.get("contentType"),
                p.get("ownerId"), p.get("ownerTeam"), p.get("approvedAt"),
                p.get("effectiveDate"), p.get("nextReviewDate"),
                p.get("reviewFrequencyMonths"), p.get("controlTags"),
                p.get("frameworkRefs"), p.get("tenantId"), p.get("createdAt")));

        List<Predicate> where = new java.util.ArrayList<>();
        where.add(visibleToTenant(cb, p, tenantId, systemUser));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.toLowerCase() + "%";
            where.add(cb.or(
                    cb.like(cb.lower(p.get("title")),     like),
                    cb.like(cb.lower(p.get("policyRef")), like)));
        }
        if (status != null) where.add(cb.equal(p.get("status"), status));

        // Origin filter — GLOBAL is tenant_id IS NULL, ORG is this tenant's own.
        // Applied on top of visibleToTenant rather than replacing it, so ORG can
        // never widen to another tenant's rows even if the parameter is forged.
        if ("GLOBAL".equalsIgnoreCase(origin)) where.add(cb.isNull(p.get("tenantId")));
        else if ("ORG".equalsIgnoreCase(origin)) where.add(cb.equal(p.get("tenantId"), tenantId));

        cq.where(where.toArray(new Predicate[0]));
        cq.orderBy(cb.asc(p.get("title")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> findByTenantIdOrderByTitleAsc(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(visibleToTenant(cb, p, tenantId));
        cq.orderBy(cb.asc(p.get("title")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> findByTenantIdAndStatus(Long tenantId, AuditPolicy.PolicyStatus status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                visibleToTenant(cb, p, tenantId),
                cb.equal(p.get("status"), status)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> findByPolicyRefForTenant(String policyRef, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                cb.equal(p.get("policyRef"), policyRef),
                visibleToTenant(cb, p, tenantId)
        );
        cq.orderBy(cb.desc(p.get("tenantId")));   // tenant row first, global (NULL) last
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> searchByTitle(Long tenantId, String search) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                visibleToTenant(cb, p, tenantId),
                cb.like(cb.lower(p.get("title")), "%" + search.toLowerCase() + "%")
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> findDueForReview(Long tenantId, LocalDate reviewBefore) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                visibleToTenant(cb, p, tenantId),
                cb.lessThanOrEqualTo(p.get("nextReviewDate"), reviewBefore),
                cb.equal(p.get("status"), AuditPolicy.PolicyStatus.APPROVED)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public boolean policyRefExists(String policyRef, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        // IS NULL, not "= null" — the whole reason this exists.
        Predicate scope = tenantId == null
                ? cb.isNull(p.get("tenantId"))
                : cb.equal(p.get("tenantId"), tenantId);
        cq.select(cb.count(p)).where(cb.and(cb.equal(p.get("policyRef"), policyRef), scope));
        Long r = em.createQuery(cq).getSingleResult();
        return r != null && r > 0;
    }
}