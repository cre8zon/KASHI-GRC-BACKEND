package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

/** Weight-sum and tag-projection queries live in the Custom fragment (Criteria API). */
@Repository
public interface AssessmentQuestionInstanceRepository
        extends JpaRepository<AssessmentQuestionInstance, Long>,
        AssessmentQuestionInstanceRepositoryCustom {

    List<AssessmentQuestionInstance> findByAssessmentIdOrderByOrderNo(Long assessmentId);

    List<AssessmentQuestionInstance> findBySectionInstanceIdOrderByOrderNo(Long sectionInstanceId);

    long countByAssessmentId(Long assessmentId);

    long countBySectionInstanceId(Long sectionInstanceId);

    List<AssessmentQuestionInstance> findByAssessmentIdAndAssignedUserIdOrderByOrderNo(
            Long assessmentId, Long assignedUserId);

    List<AssessmentQuestionInstance> findBySectionInstanceIdAndAssignedUserIdIsNullOrderByOrderNo(
            Long sectionInstanceId);

    List<AssessmentQuestionInstance> findBySectionInstanceIdInOrderByOrderNo(
            Collection<Long> sectionInstanceIds);
}
