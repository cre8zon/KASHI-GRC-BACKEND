package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
public interface AssessmentQuestionInstanceRepository
        extends JpaRepository<AssessmentQuestionInstance, Long> {

    List<AssessmentQuestionInstance> findByAssessmentIdOrderByOrderNo(Long assessmentId);

    List<AssessmentQuestionInstance> findBySectionInstanceIdOrderByOrderNo(Long sectionInstanceId);

    long countByAssessmentId(Long assessmentId);
    long countBySectionInstanceId(Long sectionInstanceId);

    /** Step 6 — Contributor fetches only questions assigned to them */
    List<AssessmentQuestionInstance> findByAssessmentIdAndAssignedUserIdOrderByOrderNo(
            Long assessmentId, Long assignedUserId);

    /** Step 5 — check if all questions in a section are assigned */
    List<AssessmentQuestionInstance> findBySectionInstanceIdAndAssignedUserIdIsNullOrderByOrderNo(
            Long sectionInstanceId);

    /**
     * Bulk fetch all questions across multiple section instances in one query.
     * Replaces N per-section queries in getMySections() and bulk-assembly endpoints.
     */
    List<AssessmentQuestionInstance> findBySectionInstanceIdInOrderByOrderNo(
            Collection<Long> sectionInstanceIds);

    /**
     * SUM weight directly at DB — replaces load-all-questions-to-sum pattern.
     */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN q.weight IS NOT NULL THEN q.weight ELSE 1.0 END), 0.0)
        FROM AssessmentQuestionInstance q
        WHERE q.assessmentId = :assessmentId
        """)
    Double sumWeightByAssessmentId(@Param("assessmentId") Long assessmentId);

    // ── Evidence reuse engine — tag snapshot matching ─────────────────────────

    /**
     * Find all question instances across all assessments for a tenant
     * that carry a specific questionTagSnapshot.
     *
     * Called by EvidenceReuseEngine.propagate() to auto-link uploaded evidence
     * to all questions with the matching tag, regardless of which assessment they belong to.
     *
     * Returns id and assignedUserId so the engine can notify the responsible actor.
     *
     * NOTE: If AssessmentQuestionInstance uses a different field name for the assigned user
     * (e.g. contributorUserId, responderUserId), update the field name in the query below.
     */
    @Query("""
        SELECT new map(q.id as id, q.assignedUserId as assignedUserId)
        FROM AssessmentQuestionInstance q
        WHERE q.tenantId = :tenantId
          AND q.questionTagSnapshot = :tag
    """)
    List<Map<String, Object>> findByTenantIdAndQuestionTagSnapshot(
            @Param("tenantId") Long tenantId,
            @Param("tag") String tag
    );
}