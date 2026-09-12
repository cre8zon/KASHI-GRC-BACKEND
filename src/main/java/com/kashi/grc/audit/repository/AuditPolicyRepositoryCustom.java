package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicy;

import java.time.LocalDate;
import java.util.List;

/**
 * Criteria API fragment for AuditPolicyRepository.
 * All methods include global policies (tenantId IS NULL) alongside tenant-scoped.
 */
public interface AuditPolicyRepositoryCustom {

    /** Count policies visible to a tenant (global + tenant). */
    /** systemUser sees globals at ANY status; a tenant sees only APPROVED ones. */
    long countForTenant(Long tenantId, boolean systemUser);

    /**
     * Ref-uniqueness check that works for GLOBAL policies too.
     *
     * The derived existsByPolicyRefAndTenantId(ref, null) generates
     * "tenant_id = null" in SQL, which is never true — so for a global policy the
     * duplicate check always passed and the generator could hand out a ref that
     * already existed. Criteria lets us emit IS NULL instead.
     */
    boolean policyRefExists(String policyRef, Long tenantId);

    /** All policies visible to a tenant, ORDER BY title. */
    List<AuditPolicy> findByTenantIdOrderByTitleAsc(Long tenantId);

    /**
     * Summary projection for the list screen — selects columns explicitly so the
     * policy document (content_body) is never read. See AuditPolicySummary.
     *
     * search and status are both optional; pass null to skip either filter, which
     * lets one method serve all three branches the controller had.
     */
    List<AuditPolicySummary> findSummariesForTenant(Long tenantId, String search,
                                                    AuditPolicy.PolicyStatus status,
                                                    String origin, boolean systemUser);

    /** Policies visible to a tenant filtered by status. */
    List<AuditPolicy> findByTenantIdAndStatus(Long tenantId, AuditPolicy.PolicyStatus status);

    /**
     * Lookup by ref: tenant-scoped row sorts before global (ORDER BY tenantId DESC —
     * MySQL sorts NULLs last on DESC), so callers taking the first element get
     * the tenant override with a global fallback.
     */
    List<AuditPolicy> findByPolicyRefForTenant(String policyRef, Long tenantId);

    /** Case-insensitive title contains-search across visible policies. */
    List<AuditPolicy> searchByTitle(Long tenantId, String search);

    /** APPROVED policies with review due on/before the given date — notifications. */
    List<AuditPolicy> findDueForReview(Long tenantId, LocalDate reviewBefore);
}