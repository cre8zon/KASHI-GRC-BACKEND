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

    /**
     * Every assistant's lock on one section, for reviewerReopenSection to clear.
     *
     * Reopening a reviewer section clears reviewerSubmittedAt on the section
     * instance, which is the REVIEWER's lock. Each review assistant holds a
     * separate one — a row here per (section, assistant) — and leaving those in
     * place meant an assistant could not re-lock a section that had been
     * reopened: assistantSubmitSection sees their row and returns
     * ALREADY_SUBMITTED.
     *
     * The vendor-side mirror already existed:
     * ContributorSectionSubmissionRepository.findBySectionInstanceId, which
     * contributor-reopen uses for exactly this.
     */
    List<ReviewerAssistantSectionSubmission> findBySectionInstanceId(Long sectionInstanceId);

    long countByTaskInstanceId(Long taskInstanceId);
}