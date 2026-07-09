package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditControlInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Derived-name queries only. All former @Query methods live in
 * AuditControlInstanceRepositoryCustom, implemented via the JPA Criteria API
 * in AuditControlInstanceRepositoryImpl.
 */
@Repository
public interface AuditControlInstanceRepository
        extends JpaRepository<AuditControlInstance, Long>, AuditControlInstanceRepositoryCustom {

    // ── Basic retrieval ───────────────────────────────────────────────────────

    List<AuditControlInstance> findBySectionInstanceIdOrderByOrderNoAsc(Long sectionInstanceId);

    List<AuditControlInstance> findByEngagementId(Long engagementId);

    long countByEngagementId(Long engagementId);

    // ── Assignment ────────────────────────────────────────────────────────────

    List<AuditControlInstance> findByEngagementIdAndAssignedAuditorId(Long engagementId, Long auditorId);

    List<AuditControlInstance> findByEngagementIdAndAuditeeAssignedUserId(Long engagementId, Long auditeeUserId);

    // ── Workflow / tenant lookups ─────────────────────────────────────────────

    boolean existsByEngagementIdAndWorkflowInstanceId(Long engagementId, Long workflowInstanceId);

    List<AuditControlInstance> findByTenantId(Long tenantId);

    List<AuditControlInstance> findByTenantIdOrderByControlCodeSnapshotAsc(Long tenantId);

    // ── Per-result counts for syncEngagementScore() ───────────────────────────

    long countByEngagementIdAndTestResult(Long engagementId, AuditControlInstance.TestResult testResult);

    long countByEngagementIdAndAuditeeEvidenceSubmitted(Long engagementId, boolean submitted);
}
