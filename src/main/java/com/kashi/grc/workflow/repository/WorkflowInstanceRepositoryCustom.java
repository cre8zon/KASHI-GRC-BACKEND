package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowInstance;
import java.util.Optional;

/** Criteria API fragment for WorkflowInstanceRepository. */
public interface WorkflowInstanceRepositoryCustom {

    /**
     * Most recent IN_PROGRESS workflow instance for an entity
     * (former JPQL ORDER BY createdAt DESC LIMIT 1 → setMaxResults(1)).
     */
    Optional<WorkflowInstance> findActiveByEntityTypeAndEntityId(String entityType, Long entityId);

    /**
     * How much a tenant uses each workflow: rows of {workflowId, total, open},
     * where open = PENDING, IN_PROGRESS or ON_HOLD. Workflows with no
     * instances are simply absent. Feeds the workflow catalogue's "In use".
     */
    java.util.List<Object[]> countUsageByWorkflowForTenant(Long tenantId, java.util.Collection<Long> workflowIds);
}
