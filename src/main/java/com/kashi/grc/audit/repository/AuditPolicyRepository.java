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

    /**
     * How many copies of a platform policy this tenant already has.
     *
     * previousVersionId is set by customisePolicy to the source id. Copies are
     * NOT restricted — this is for telling the user they already have one, not
     * for blocking a second.
     */
    long countByPreviousVersionIdAndTenantId(Long previousVersionId, Long tenantId);

    /** Across ALL tenants — how many organisations have adopted this platform
     *  policy. Guards unpublish: un-approving a source that tenants already
     *  copied would orphan their copies. */
    long countByPreviousVersionId(Long previousVersionId);

    /** First existing copy, for telling the user which one they already have. */
    Optional<AuditPolicy> findFirstByPreviousVersionIdAndTenantId(Long previousVersionId, Long tenantId);

    // Exact tenant match — used by CSV import upsert
    Optional<AuditPolicy> findByPolicyRefAndTenantId(String policyRef, Long tenantId);
}