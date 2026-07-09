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
}
