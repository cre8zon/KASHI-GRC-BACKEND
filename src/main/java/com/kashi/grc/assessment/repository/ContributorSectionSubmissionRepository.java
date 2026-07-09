package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.ContributorSectionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** countDistinctSectionsWithAssignments lives in the Custom fragment (Criteria API). */
public interface ContributorSectionSubmissionRepository
        extends JpaRepository<ContributorSectionSubmission, Long>,
        ContributorSectionSubmissionRepositoryCustom {

    Optional<ContributorSectionSubmission> findBySectionInstanceIdAndContributorUserId(
            Long sectionInstanceId, Long contributorUserId);

    List<ContributorSectionSubmission> findByTaskInstanceId(Long taskInstanceId);

    List<ContributorSectionSubmission> findByAssessmentIdAndContributorUserId(
            Long assessmentId, Long contributorUserId);

    boolean existsBySectionInstanceIdAndContributorUserId(
            Long sectionInstanceId, Long contributorUserId);

    long countByTaskInstanceId(Long taskInstanceId);
}
