package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.TenantIntegrationCheck;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

/** JPA Criteria API implementation of TenantIntegrationCheckRepositoryCustom. */
public class TenantIntegrationCheckRepositoryImpl implements TenantIntegrationCheckRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public int deactivateByTenantAndIntegration(Long tenantId, String integrationKey) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<TenantIntegrationCheck> cu = cb.createCriteriaUpdate(TenantIntegrationCheck.class);
        Root<TenantIntegrationCheck> c = cu.from(TenantIntegrationCheck.class);
        cu.set(c.get("isActive"), false)
          .where(
                  cb.equal(c.get("tenantId"), tenantId),
                  cb.equal(c.get("integrationKey"), integrationKey)
          );
        return em.createQuery(cu).executeUpdate();
    }

    /** result = null means "never run" (lastRunStatus IS NULL). */
    private long countActiveByStatus(Long tenantId, String integrationKey, String result) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<TenantIntegrationCheck> c = cq.from(TenantIntegrationCheck.class);
        Predicate statusPredicate = (result == null)
                ? cb.isNull(c.get("lastRunStatus"))
                : cb.equal(c.get("lastRunStatus"), result);
        cq.select(cb.count(c)).where(
                cb.equal(c.get("tenantId"), tenantId),
                cb.equal(c.get("integrationKey"), integrationKey),
                cb.isTrue(c.get("isActive")),
                statusPredicate
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public long countPassingByTenantAndIntegration(Long tenantId, String integrationKey) {
        return countActiveByStatus(tenantId, integrationKey, "PASS");
    }

    @Override
    public long countFailingByTenantAndIntegration(Long tenantId, String integrationKey) {
        return countActiveByStatus(tenantId, integrationKey, "FAIL");
    }

    @Override
    public long countNeverRunByTenantAndIntegration(Long tenantId, String integrationKey) {
        return countActiveByStatus(tenantId, integrationKey, null);
    }
}
