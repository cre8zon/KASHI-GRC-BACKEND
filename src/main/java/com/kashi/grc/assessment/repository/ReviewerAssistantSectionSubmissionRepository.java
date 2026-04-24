package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.ReviewerAssistantSectionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewerAssistantSectionSubmissionRepository
        extends JpaRepository<ReviewerAssistantSectionSubmission, Long> {

    boolean existsBySectionInstanceIdAndAssistantUserId(Long sectionInstanceId, Long assistantUserId);

    List<ReviewerAssistantSectionSubmission> findByAssessmentIdAndAssistantUserId(
            Long assessmentId, Long assistantUserId);

    long countByTaskInstanceId(Long taskInstanceId);

    /**
     * Count distinct sections that have at least one question
     * with reviewerAssignedUserId = assistantUserId.
     * Mirrors ContributorSectionSubmissionRepository.countDistinctSectionsWithAssignments.
     */
    @Query("""
        SELECT COUNT(DISTINCT qi.sectionInstanceId)
        FROM   AssessmentQuestionInstance qi
        WHERE  qi.assessmentId = :assessmentId
        AND    qi.reviewerAssignedUserId = :assistantUserId
    """)
    long countDistinctSectionsWithAssignments(
            @Param("assessmentId")  Long assessmentId,
            @Param("assistantUserId") Long assistantUserId);
}