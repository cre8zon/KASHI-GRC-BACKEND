package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

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
     *
     * Replaces the per-section findBySectionInstanceIdOrderByOrderNo pattern
     * in getMySections() and other bulk-assembly endpoints where each section's
     * questions were loaded one section at a time (N queries for N sections).
     *
     * Used in getMySections() to pre-load all questions before the mapping loop
     * so name lookups, response lookups, and option lookups can be done in bulk.
     */
    List<AssessmentQuestionInstance> findBySectionInstanceIdInOrderByOrderNo(
            Collection<Long> sectionInstanceIds);

    /**
     * SUM weight directly at the DB — replaces the load-all-questions-to-sum-weight
     * pattern in AssessmentController.listAssessments():
     *
     *   questionInstanceRepository.findByAssessmentIdOrderByOrderNo(a.getId())
     *           .stream().mapToDouble(q -> q.getWeight() != null ? q.getWeight() : 1.0).sum()
     *
     * That pattern loaded every question instance row into Java heap just to sum one column.
     * This query does it in a single SQL SUM with a CASE for null weights.
     * Returns 0.0 when the assessment has no question instances.
     */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN q.weight IS NOT NULL THEN q.weight ELSE 1.0 END), 0.0)
        FROM AssessmentQuestionInstance q
        WHERE q.assessmentId = :assessmentId
        """)
    Double sumWeightByAssessmentId(@Param("assessmentId") Long assessmentId);
}
