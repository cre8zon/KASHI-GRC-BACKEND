package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssessmentResponseRepository extends JpaRepository<AssessmentResponse, Long> {
    List<AssessmentResponse> findByAssessmentId(Long assessmentId);
    Optional<AssessmentResponse> findByAssessmentIdAndQuestionInstanceId(Long assessmentId, Long questionInstanceId);
    long countByAssessmentId(Long assessmentId);

    @Query("SELECT COUNT(r) FROM AssessmentResponse r " +
            "JOIN AssessmentQuestionInstance q ON q.id = r.questionInstanceId " +
            "WHERE r.assessmentId = :assessmentId AND q.sectionInstanceId = :sectionInstanceId")
    long countByAssessmentIdAndSectionInstanceId(
            @Param("assessmentId") Long assessmentId,
            @Param("sectionInstanceId") Long sectionInstanceId);

    /**
     * Count questions across a set of sections that have been evaluated
     * (reviewerStatus is not null and not PENDING).
     * Used by saveReviewerEval to detect when SCORE_ANSWERS section is complete.
     */
    @Query("SELECT COUNT(r) FROM AssessmentResponse r " +
            "JOIN AssessmentQuestionInstance q ON q.id = r.questionInstanceId " +
            "WHERE r.assessmentId = :assessmentId " +
            "AND q.sectionInstanceId IN :sectionInstanceIds " +
            "AND r.reviewerStatus IS NOT NULL " +
            "AND r.reviewerStatus <> 'PENDING'")
    long countEvaluatedInSections(
            @Param("assessmentId") Long assessmentId,
            @Param("sectionInstanceIds") List<Long> sectionInstanceIds);

    /**
     * Count total questions across a set of sections.
     * Used alongside countEvaluatedInSections to determine completion percentage.
     */
    @Query("SELECT COUNT(q) FROM AssessmentQuestionInstance q " +
            "WHERE q.sectionInstanceId IN :sectionInstanceIds")
    long countTotalInSections(
            @Param("sectionInstanceIds") List<Long> sectionInstanceIds);

    default Double sumScoreByAssessmentId(Long assessmentId) {
        return findByAssessmentId(assessmentId).stream()
                .filter(r -> r.getScoreEarned() != null)
                .mapToDouble(r -> r.getScoreEarned())
                .sum();
    }
}