package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, Long> {

    Optional<WorkflowInstance> findByTenantIdAndEntityTypeAndEntityIdAndStatusIn(
            Long tenantId, String entityType, Long entityId, List<WorkflowStatus> statuses);

    List<WorkflowInstance> findAllByTenantIdAndEntityTypeAndEntityIdAndStatusIn(
            Long tenantId, String entityType, Long entityId, List<WorkflowStatus> statuses);

    boolean existsByTenantIdAndEntityTypeAndEntityIdAndStatusIn(
            Long tenantId, String entityType, Long entityId, List<WorkflowStatus> statuses);

    List<WorkflowInstance> findByTenantIdAndStatus(Long tenantId, WorkflowStatus status);

    List<WorkflowInstance> findByTenantIdAndEntityTypeAndEntityId(
            Long tenantId, String entityType, Long entityId);

    List<WorkflowInstance> findByWorkflowIdAndStatus(Long workflowId, WorkflowStatus status);

    Optional<WorkflowInstance> findByIdAndTenantId(Long id, Long tenantId);

    long countByWorkflowId(Long workflowId);
}