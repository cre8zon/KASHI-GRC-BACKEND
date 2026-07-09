package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.ReviewerAssistantSectionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** countDistinctSectionsWithAssignments lives in the Custom fragment (Criteria API). */
@Repository
public interface ReviewerAssistantSectionSubmissionRepository
        extends JpaRepository<ReviewerAssistantSectionSubmission, Long>,
        ReviewerAssistantSectionSubmissionRepositoryCustom {

    boolean existsBySectionInstanceIdAndAssistantUserId(Long sectionInstanceId, Long assistantUserId);

    List<ReviewerAssistantSectionSubmission> findByAssessmentIdAndAssistantUserId(
            Long assessmentId, Long assistantUserId);

    long countByTaskInstanceId(Long taskInstanceId);
}
