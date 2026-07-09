package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

/** JPA Criteria API implementation of EvidenceRecordRepositoryCustom. */
public class EvidenceRecordRepositoryImpl implements EvidenceRecordRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long countActiveByTenantAndTag(Long tenantId, String tag) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EvidenceRecord> e = cq.from(EvidenceRecord.class);
        cq.select(cb.count(e)).where(
                cb.equal(e.get("tenantId"), tenantId),
                cb.equal(e.get("controlTag"), tag),
                cb.isFalse(e.get("expired"))
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }
}
