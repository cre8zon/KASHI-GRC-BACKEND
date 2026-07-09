package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.EngagementIntegrationSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

/** JPA Criteria API implementation of EngagementIntegrationSnapshotRepositoryCustom. */
public class EngagementIntegrationSnapshotRepositoryImpl
        implements EngagementIntegrationSnapshotRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public int deactivateByEngagementId(Long engagementId, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<EngagementIntegrationSnapshot> cu =
                cb.createCriteriaUpdate(EngagementIntegrationSnapshot.class);
        Root<EngagementIntegrationSnapshot> s = cu.from(EngagementIntegrationSnapshot.class);
        cu.set(s.get("isActive"), false)
          .where(
                  cb.equal(s.get("engagementId"), engagementId),
                  cb.equal(s.get("tenantId"), tenantId)
          );
        return em.createQuery(cu).executeUpdate();
    }

    private long countByResult(Long engagementId, Long tenantId, String result) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EngagementIntegrationSnapshot> s = cq.from(EngagementIntegrationSnapshot.class);
        cq.select(cb.count(s)).where(
                cb.equal(s.get("engagementId"), engagementId),
                cb.equal(s.get("tenantId"), tenantId),
                cb.equal(s.get("lastResult"), result)
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public long countPassingByEngagementId(Long engagementId, Long tenantId) {
        return countByResult(engagementId, tenantId, "PASS");
    }

    @Override
    public long countFailingByEngagementId(Long engagementId, Long tenantId) {
        return countByResult(engagementId, tenantId, "FAIL");
    }

    @Override
    public long countNeverRunByEngagementId(Long engagementId, Long tenantId) {
        return countByResult(engagementId, tenantId, "NOT_RUN");
    }
}
