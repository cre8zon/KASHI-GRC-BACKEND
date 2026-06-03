package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Finds the active (IN_PROGRESS) workflow instance for a specific entity.
     *
     * Used by WorkflowAccessService.resolveForModule() to check whether a SoD evaluation
     * is needed when a user navigates to a module detail page (non-task context).
     *
     * Returns empty if no IN_PROGRESS instance exists for this entity — which is the
     * common case for list pages and entities without an active workflow.
     */
    @Query("""
        SELECT wi FROM WorkflowInstance wi
        WHERE wi.entityType = :entityType
          AND wi.entityId   = :entityId
          AND wi.status     = 'IN_PROGRESS'
        ORDER BY wi.createdAt DESC
        LIMIT 1
        """)
    Optional<WorkflowInstance> findActiveByEntityTypeAndEntityId(
            @Param("entityType") String entityType,
            @Param("entityId")   Long entityId);
}