package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.enums.TaskStatus;

import java.util.Collection;
import java.util.List;

/** Criteria API fragment for TaskInstanceRepository. */
public interface TaskInstanceRepositoryCustom {

    /** Whether the user holds a task in any of the given statuses within a workflow instance. */
    boolean existsByUserIdAndWorkflowInstanceIdAndStatusIn(
            Long userId, Long workflowInstanceId, Collection<TaskStatus> statuses);

    /** Whether the user holds any task within a workflow instance. */
    boolean existsByUserIdAndWorkflowInstanceId(Long userId, Long workflowInstanceId);

    /**
     * The user's approved ACTOR tasks within a workflow instance.
     *
     * NOTE: the former JPQL matched status IN ('APPROVED', 'COMPLETED'), but
     * COMPLETED is not a TaskStatus constant — Hibernate could never bind it,
     * so the clause effectively matched APPROVED only (and would fail HQL
     * validation on strict configs). This implementation encodes the working
     * behaviour explicitly: status = APPROVED.
     */
    List<TaskInstance> findActorTasksForInstance(Long workflowInstanceId, Long userId);
}
