package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentSectionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssessmentSectionInstanceRepository
        extends JpaRepository<AssessmentSectionInstance, Long> {

    // ← NEW: primary query via templateInstanceId
    List<AssessmentSectionInstance> findByTemplateInstanceIdOrderBySectionOrderNo(Long templateInstanceId);

    /** Step 5 — Responder fetches only sections assigned to them */
    // ← NEW: responder fetch via templateInstanceId
    List<AssessmentSectionInstance> findByTemplateInstanceIdAndAssignedUserIdOrderBySectionOrderNo(
            Long templateInstanceId, Long assignedUserId);
    

    List<AssessmentSectionInstance> findByTemplateInstanceIdAndReviewerAssignedUserIdOrderBySectionOrderNo(
            Long templateInstanceId, Long reviewerAssignedUserId);
}