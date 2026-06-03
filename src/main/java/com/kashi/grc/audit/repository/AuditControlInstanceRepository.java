package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditControlInstance;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface AuditControlInstanceRepository extends JpaRepository<AuditControlInstance, Long> {

    // ── Basic retrieval ───────────────────────────────────────────────────────

    List<AuditControlInstance> findBySectionInstanceIdOrderByOrderNoAsc(Long sectionInstanceId);

    List<AuditControlInstance> findByEngagementId(Long engagementId);

    long countByEngagementId(Long engagementId);

    // ── Path-based subtree queries ────────────────────────────────────────────

    @Query("""
        SELECT c FROM AuditControlInstance c
        WHERE c.engagementId = :engagementId
          AND c.sectionPath LIKE CONCAT(:pathPrefix, '%')
        ORDER BY c.sectionPath ASC, c.orderNo ASC
    """)
    List<AuditControlInstance> findByEngagementIdAndSectionPathStartingWith(
            @Param("engagementId") Long engagementId,
            @Param("pathPrefix") String pathPrefix
    );

    // ── Library reference ─────────────────────────────────────────────────────

    @Query("""
        SELECT c FROM AuditControlInstance c
        WHERE c.sectionInstanceId IN (
            SELECT s.id FROM AuditSectionInstance s WHERE s.originalSectionId = :originalSectionId
        )
    """)
    List<AuditControlInstance> findBySectionInstanceId_OriginalSectionId(
            @Param("originalSectionId") Long originalSectionId
    );

    // ── Assignment ────────────────────────────────────────────────────────────

    List<AuditControlInstance> findByEngagementIdAndAssignedAuditorId(Long engagementId, Long auditorId);

    List<AuditControlInstance> findByEngagementIdAndAuditeeAssignedUserId(Long engagementId, Long auditeeUserId);

    /**
     * Distinct auditee user IDs who have been assigned at least one control in this engagement.
     * Used by AuditEngagementStepListener to scope AUDITEE-side workflow tasks.
     */
    @Query("""
        SELECT DISTINCT c.auditeeAssignedUserId FROM AuditControlInstance c
        WHERE c.engagementId = :engagementId
          AND c.auditeeAssignedUserId IS NOT NULL
    """)
    List<Long> findDistinctAssignedAuditeeIdsByEngagementId(@Param("engagementId") Long engagementId);

    /**
     * Controls approaching or past their evidence due date with unsubmitted evidence.
     * Used by AuditEvidenceReminderScheduler — runs daily at 08:00.
     * :deadline = LocalDate.now().plusDays(3)
     */
    @Query("""
        SELECT c FROM AuditControlInstance c
        WHERE c.auditeeAssignedUserId IS NOT NULL
          AND c.auditeeEvidenceSubmitted = false
          AND c.evidenceDueDate IS NOT NULL
          AND c.evidenceDueDate <= :deadline
    """)
    List<AuditControlInstance> findDueForEvidenceReminder(@Param("deadline") java.time.LocalDate deadline);

    // ── Evidence reuse engine — tag snapshot matching ─────────────────────────

    /**
     * Find all audit control instances across all engagements for a tenant
     * that carry a specific controlTagSnapshot.
     *
     * Called by EvidenceReuseEngine.propagate() to auto-link uploaded evidence
     * to all controls with the matching tag, regardless of which engagement they belong to.
     *
     * Returns id and assignedAuditorId so the engine can notify the responsible actor.
     */
    @Query("""
        SELECT new map(c.id as id, c.assignedAuditorId as assignedAuditorId)
        FROM AuditControlInstance c
        WHERE c.engagementId IN (
            SELECT e.id FROM AuditEngagement e WHERE e.tenantId = :tenantId
        )
        AND c.controlTagSnapshot = :tag
    """)
    List<Map<String, Object>> findByTenantIdAndControlTagSnapshot(
            @Param("tenantId") Long tenantId,
            @Param("tag") String tag
    );

    // ── Statistics ────────────────────────────────────────────────────────────

    @Query("""
        SELECT COUNT(c) FROM AuditControlInstance c
        WHERE c.engagementId = :engagementId
          AND c.testResult IS NOT NULL
          AND c.testResult != 'NOT_TESTED'
    """)
    long countTestedByEngagement(@Param("engagementId") Long engagementId);

    @Query("""
        SELECT c.testResult, COUNT(c) FROM AuditControlInstance c
        WHERE c.engagementId = :engagementId
        GROUP BY c.testResult
    """)
    List<Object[]> countByResultForEngagement(@Param("engagementId") Long engagementId);

    @Query("""
        SELECT COUNT(c) FROM AuditControlInstance c
        WHERE c.engagementId = :engagementId AND c.findingLinked = true
    """)
    long countFindingsLinkedByEngagement(@Param("engagementId") Long engagementId);

    boolean existsByEngagementIdAndWorkflowInstanceId(Long engagementId, Long workflowInstanceId);

    List<AuditControlInstance> findByTenantId(Long tenantId);

    List<AuditControlInstance> findByTenantIdOrderByControlCodeSnapshotAsc(Long tenantId);
}