package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * All tenant-overlay list/search/count methods live in AuditPolicyRepositoryCustom,
 * implemented via the JPA Criteria API in AuditPolicyRepositoryImpl.
 */
@Repository
public interface AuditPolicyRepository
        extends JpaRepository<AuditPolicy, Long>, AuditPolicyRepositoryCustom {

    boolean existsByPolicyRefAndTenantId(String policyRef, Long tenantId);

    // Exact tenant match — used by CSV import upsert
    Optional<AuditPolicy> findByPolicyRefAndTenantId(String policyRef, Long tenantId);
}
