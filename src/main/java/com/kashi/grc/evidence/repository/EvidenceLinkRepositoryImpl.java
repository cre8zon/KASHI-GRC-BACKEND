package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceLink;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of EvidenceLinkRepositoryCustom.
 * Enum string literals → EvidenceLink.Status constants (type-safe).
 */
public class EvidenceLinkRepositoryImpl implements EvidenceLinkRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public int expireByEvidenceRecordId(Long recordId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<EvidenceLink> cu = cb.createCriteriaUpdate(EvidenceLink.class);
        Root<EvidenceLink> l = cu.from(EvidenceLink.class);
        cu.set(l.get("status"), EvidenceLink.Status.EXPIRED)
                .where(
                        cb.equal(l.get("evidenceRecordId"), recordId),
                        l.get("status").in(List.of(
                                EvidenceLink.Status.PENDING_REVIEW,
                                EvidenceLink.Status.ACCEPTED))
                );
        return em.createQuery(cu).executeUpdate();
    }

    @Override
    public List<EvidenceLink> findPendingReviewForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<EvidenceLink> cq = cb.createQuery(EvidenceLink.class);
        Root<EvidenceLink> l = cq.from(EvidenceLink.class);
        cq.where(
                cb.equal(l.get("tenantId"), tenantId),
                cb.equal(l.get("status"), EvidenceLink.Status.PENDING_REVIEW),
                cb.isTrue(l.get("autoLinked"))
        );
        cq.orderBy(cb.desc(l.get("linkedAt")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public java.util.Set<Long> entityIdsWithAnyLink(String entityType, java.util.List<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return new java.util.HashSet<>();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EvidenceLink> l = cq.from(EvidenceLink.class);
        // Select DISTINCT target ids that have any link (any status).
        cq.select(l.get("targetEntityId")).distinct(true).where(
                cb.equal(l.get("targetEntityType"), entityType),
                l.get("targetEntityId").in(entityIds)
        );
        return new java.util.HashSet<>(em.createQuery(cq).getResultList());
    }

    @Override
    public long countAcceptedForEntity(String entityType, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EvidenceLink> l = cq.from(EvidenceLink.class);
        cq.select(cb.count(l)).where(
                cb.equal(l.get("targetEntityType"), entityType),
                cb.equal(l.get("targetEntityId"), entityId),
                cb.equal(l.get("status"), EvidenceLink.Status.ACCEPTED)
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<EvidenceLink> findControlEvidenceUsedByTest(Long testInstanceId, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<EvidenceLink> cq = cb.createQuery(EvidenceLink.class);
        Root<EvidenceLink> cl = cq.from(EvidenceLink.class);

        // Evidence records linked to the test instance
        Subquery<Long> testEvidence = cq.subquery(Long.class);
        Root<EvidenceLink> tl = testEvidence.from(EvidenceLink.class);
        testEvidence.select(tl.get("evidenceRecordId")).where(
                cb.equal(tl.get("targetEntityType"), "AUDIT_TEST_INSTANCE"),
                cb.equal(tl.get("targetEntityId"), testInstanceId),
                cb.equal(tl.get("tenantId"), tenantId)
        );

        cq.where(
                cb.equal(cl.get("targetEntityType"), "AUDIT_CONTROL_INSTANCE"),
                cb.equal(cl.get("tenantId"), tenantId),
                cl.get("evidenceRecordId").in(testEvidence)
        );
        cq.orderBy(cb.asc(cl.get("linkedAt")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<EvidenceLink> findTestsUsingControlEvidence(Long evidenceRecordId, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<EvidenceLink> cq = cb.createQuery(EvidenceLink.class);
        Root<EvidenceLink> tl = cq.from(EvidenceLink.class);
        cq.where(
                cb.equal(tl.get("targetEntityType"), "AUDIT_TEST_INSTANCE"),
                cb.equal(tl.get("tenantId"), tenantId),
                cb.equal(tl.get("evidenceRecordId"), evidenceRecordId)
        );
        cq.orderBy(cb.asc(tl.get("linkedAt")));
        return em.createQuery(cq).getResultList();
    }
}