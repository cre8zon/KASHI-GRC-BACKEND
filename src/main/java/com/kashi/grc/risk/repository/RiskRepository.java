package com.kashi.grc.risk.repository;

import com.kashi.grc.risk.domain.Risk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Derived-name queries only. Anything needing a dynamic predicate lives in
 * RiskRepositoryCustom / RiskRepositoryImpl via the JPA Criteria API —
 * no @Query annotations anywhere in this project.
 */
@Repository
public interface RiskRepository extends JpaRepository<Risk, Long>,
        JpaSpecificationExecutor<Risk>,
        RiskRepositoryCustom {

    /** A tenant's own entry. Returns empty for a library row — deliberately. */
    Optional<Risk> findByIdAndTenantId(Long id, Long tenantId);

    /** Library rows only (tenant_id IS NULL). */
    List<Risk> findByTenantIdIsNullAndIsDeletedFalse();

    /** Already-adopted check: one copy of a given library row per tenant. */
    boolean existsBySourceRiskIdAndTenantId(Long sourceRiskId, Long tenantId);

    boolean existsByRiskRefAndTenantId(String riskRef, Long tenantId);

    Optional<Risk> findByTenantIdAndWorkflowInstanceId(Long tenantId, Long workflowInstanceId);
}
