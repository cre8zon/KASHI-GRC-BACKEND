package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowInstanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowInstanceHistoryRepository
        extends JpaRepository<WorkflowInstanceHistory, Long> {

    List<WorkflowInstanceHistory> findByWorkflowInstanceIdOrderByPerformedAtAsc(
            Long workflowInstanceId);

    List<WorkflowInstanceHistory> findByWorkflowInstanceIdAndStepIdOrderByPerformedAtAsc(
            Long workflowInstanceId, Long stepId);

    List<WorkflowInstanceHistory> findByPerformedByAndTenantId(
            Long performedBy, Long tenantId);

    List<WorkflowInstanceHistory> findByTenantIdAndEventType(
            Long tenantId, String eventType);

    /**
     * NEW — find the most recent history event of a given type for a workflow instance.
     * Used by getPreviousStepActor() to find who approved the previous step,
     * enabling the PREVIOUS_ACTOR assigner resolution without hardcoded logic.
     */
    Optional<WorkflowInstanceHistory> findTopByWorkflowInstanceIdAndEventTypeOrderByPerformedAtDesc(
            Long workflowInstanceId, String eventType);
}