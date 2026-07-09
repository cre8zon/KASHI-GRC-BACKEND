package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.AssessmentSectionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Distinct-assignee projections live in the Custom fragment (Criteria API). */
@Repository
public interface AssessmentSectionInstanceRepository
        extends JpaRepository<AssessmentSectionInstance, Long>,
        AssessmentSectionInstanceRepositoryCustom {

    List<AssessmentSectionInstance> findByTemplateInstanceIdOrderBySectionOrderNo(Long templateInstanceId);

    List<AssessmentSectionInstance> findByTemplateInstanceIdAndAssignedUserIdOrderBySectionOrderNo(
            Long templateInstanceId, Long assignedUserId);

    List<AssessmentSectionInstance> findByTemplateInstanceIdAndReviewerAssignedUserIdOrderBySectionOrderNo(
            Long templateInstanceId, Long reviewerAssignedUserId);
}
