package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.ContributorSectionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContributorSectionSubmissionRepository
        extends JpaRepository<ContributorSectionSubmission, Long> {

    Optional<ContributorSectionSubmission> findBySectionInstanceIdAndContributorUserId(
            Long sectionInstanceId, Long contributorUserId);

    List<ContributorSectionSubmission> findByTaskInstanceId(Long taskInstanceId);

    List<ContributorSectionSubmission> findByAssessmentIdAndContributorUserId(
            Long assessmentId, Long contributorUserId);

    boolean existsBySectionInstanceIdAndContributorUserId(
            Long sectionInstanceId, Long contributorUserId);

    /** Count how many section submissions exist for this contributor's task */
    long countByTaskInstanceId(Long taskInstanceId);

    /**
     * Count how many DISTINCT sections this contributor has questions in for this assessment.
     * Used to check if all sections are submitted → auto-approve sub-task.
     */
    @Query("SELECT COUNT(DISTINCT q.sectionInstanceId) FROM AssessmentQuestionInstance q " +
            "WHERE q.assessmentId = :assessmentId AND q.assignedUserId = :contributorUserId")
    long countDistinctSectionsWithAssignments(
            @Param("assessmentId") Long assessmentId,
            @Param("contributorUserId") Long contributorUserId);
}