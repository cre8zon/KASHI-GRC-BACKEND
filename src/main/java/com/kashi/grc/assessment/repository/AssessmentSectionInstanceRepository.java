package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentSectionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Distinct Responder user IDs assigned to ≥1 section in this template instance.
     * Used by VendorWorkflowActorResolver for ASSIGNMENT_SCOPED FILL steps.
     */
    @Query("SELECT DISTINCT s.assignedUserId FROM AssessmentSectionInstance s " +
            "WHERE s.templateInstanceId = :templateInstanceId AND s.assignedUserId IS NOT NULL")
    List<Long> findDistinctAssignedResponderIds(@Param("templateInstanceId") Long templateInstanceId);

    /**
     * Distinct Reviewer user IDs assigned to ≥1 section in this template instance.
     * Used by VendorWorkflowActorResolver for ASSIGNMENT_SCOPED REVIEW/EVALUATE steps.
     */
    @Query("SELECT DISTINCT s.reviewerAssignedUserId FROM AssessmentSectionInstance s " +
            "WHERE s.templateInstanceId = :templateInstanceId AND s.reviewerAssignedUserId IS NOT NULL")
    List<Long> findDistinctAssignedReviewerIds(@Param("templateInstanceId") Long templateInstanceId);
}