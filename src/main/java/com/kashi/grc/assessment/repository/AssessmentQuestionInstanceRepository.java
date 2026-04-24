package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
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

}