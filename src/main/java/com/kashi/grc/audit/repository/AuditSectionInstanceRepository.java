package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSectionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditSectionInstanceRepository extends JpaRepository<AuditSectionInstance, Long> {

    // ── Full tree retrieval ───────────────────────────────────────────────────

    /** All section instances for an engagement, ordered by path (tree order) */
    List<AuditSectionInstance> findByEngagementIdOrderByPathAscOrderNoAsc(Long engagementId);

    /** Root section instances only (no parent) */
    List<AuditSectionInstance> findByEngagementIdAndParentInstanceIdIsNullOrderByOrderNoAsc(Long engagementId);

    /** Direct children of a section instance */
    List<AuditSectionInstance> findByParentInstanceIdOrderByOrderNoAsc(Long parentInstanceId);

    /**
     * All descendants of a section instance at any depth.
     * Uses path LIKE — fast with idx_asi_path index.
     */
    @Query("""
        SELECT s FROM AuditSectionInstance s
        WHERE s.path LIKE CONCAT(:pathPrefix, '%')
          AND s.id != :instanceId
        ORDER BY s.path ASC, s.orderNo ASC
    """)
    List<AuditSectionInstance> findAllDescendants(
            @Param("instanceId") Long instanceId,
            @Param("pathPrefix") String pathPrefix
    );

    // ── Assignment queries ────────────────────────────────────────────────────

    List<AuditSectionInstance> findByEngagementIdAndAssignedAuditorId(Long engagementId, Long auditorId);

    List<AuditSectionInstance> findByEngagementIdAndAuditeeAssignedUserId(Long engagementId, Long auditeeUserId);

    /**
     * Distinct auditor user IDs who have been assigned at least one section in this engagement.
     * Used by AuditEngagementStepListener to scope AUDITOR-side workflow tasks.
     */
    @Query("""
        SELECT DISTINCT s.assignedAuditorId FROM AuditSectionInstance s
        WHERE s.engagementId = :engagementId
          AND s.assignedAuditorId IS NOT NULL
    """)
    List<Long> findDistinctAssignedAuditorIdsByEngagementId(@Param("engagementId") Long engagementId);

    /**
     * Distinct auditee user IDs who have been assigned at least one section in this engagement.
     * Used by AuditEngagementStepListener to scope AUDITEE-side workflow tasks.
     */
    @Query("""
        SELECT DISTINCT s.auditeeAssignedUserId FROM AuditSectionInstance s
        WHERE s.engagementId = :engagementId
          AND s.auditeeAssignedUserId IS NOT NULL
    """)
    List<Long> findDistinctAssignedAuditeeIdsByEngagementId(@Param("engagementId") Long engagementId);

    // ── Depth-scoped retrieval — used by AuditSectionItemRegistrar ───────────

    /**
     * Load section instances at a specific tree depth for an engagement.
     *
     * Used by AuditSectionItemRegistrar to register only the configured depth
     * as workflow section items for the assignment steps (Steps 2 and 3).
     * Default depth=0 returns root categories only (CC, A, PI, C, P for SOC 2 —
     * 5 items instead of 40+). Children inherit assignment via cascade.
     *
     * Spring Data JPA derives this query automatically — no @Query needed.
     */
    List<AuditSectionInstance> findByEngagementIdAndDepthOrderByPathAscOrderNoAsc(
            Long engagementId, int depth);

    // ── Submission state ──────────────────────────────────────────────────────

    @Query("""
        SELECT COUNT(s) FROM AuditSectionInstance s
        WHERE s.engagementId = :engagementId
          AND s.submittedAt IS NOT NULL
    """)
    long countSubmittedByEngagement(@Param("engagementId") Long engagementId);

    @Query("""
        SELECT COUNT(s) FROM AuditSectionInstance s
        WHERE s.engagementId = :engagementId
    """)
    long countTotalByEngagement(@Param("engagementId") Long engagementId);

    // ── Template linkage ──────────────────────────────────────────────────────

    List<AuditSectionInstance> findByTemplateInstanceId(Long templateInstanceId);

    Optional<AuditSectionInstance> findByEngagementIdAndOriginalSectionId(Long engagementId, Long originalSectionId);
}