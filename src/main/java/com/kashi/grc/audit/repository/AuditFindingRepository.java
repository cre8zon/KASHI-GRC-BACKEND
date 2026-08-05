package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditFindingRepository extends JpaRepository<AuditFinding, Long> {

    List<AuditFinding> findByEngagementIdAndTenantId(Long engagementId, Long tenantId);

    /**
     * Batch counterpart to findByEngagementIdAndTenantId — ONE query for a
     * whole list of engagement ids instead of one call per engagement. See
     * AuditControlInstanceRepository.findByEngagementIdIn for why.
     */
    List<AuditFinding> findByEngagementIdInAndTenantId(
            java.util.Collection<Long> engagementIds, Long tenantId);

    List<AuditFinding> findByControlInstanceIdAndTenantId(Long controlInstanceId, Long tenantId);

    Optional<AuditFinding> findByIdAndTenantId(Long id, Long tenantId);

    long countByEngagementIdAndTenantId(Long engagementId, Long tenantId);

    long countByEngagementIdAndStatusAndTenantId(
            Long engagementId, AuditFinding.Status status, Long tenantId);

    /** Auto-ref: count findings for this tenant to generate FND-YYYY-NNNN */
    long countByTenantId(Long tenantId);

    boolean existsByFindingRefAndTenantId(String findingRef, Long tenantId);

    // ── ADDED: supports GET /v1/issues/{id}/linked-findings ──────────────────
    List<AuditFinding> findByLinkedIssueIdAndTenantId(Long linkedIssueId, Long tenantId);
}