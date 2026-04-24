package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.WorkflowTaskAction;
import com.kashi.grc.workflow.enums.ActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WorkflowTaskActionRepository extends JpaRepository<WorkflowTaskAction, Long> {
    List<WorkflowTaskAction> findByTaskInstanceIdOrderByPerformedAtAsc(Long taskInstanceId);
    List<WorkflowTaskAction> findByWorkflowInstanceIdOrderByPerformedAtAsc(Long workflowInstanceId);
    List<WorkflowTaskAction> findByStepInstanceIdOrderByPerformedAtAsc(Long stepInstanceId);
    List<WorkflowTaskAction> findByPerformedByAndTenantId(Long performedBy, Long tenantId);
    List<WorkflowTaskAction> findByActionTypeAndWorkflowInstanceId(ActionType actionType, Long workflowInstanceId);
}
