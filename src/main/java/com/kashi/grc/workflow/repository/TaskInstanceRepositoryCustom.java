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

    /**
     * Count of the user's active tasks that sit on a live workflow instance.
     *
     * Mirrors the filter in WorkflowEngineService.getPendingTasksForUser exactly:
     * active task statuses, and the owning workflow instance must itself be
     * IN_PROGRESS / ON_HOLD / PENDING.
     *
     * Exists because the nav badge endpoint used to call
     * getPendingTasksForUser(userId).size() — building the entire enriched task
     * list, entity titles and all, purely to discard it and take the size. That is
     * ~14 queries on every page load for a number.
     */
    long countActiveTasksOnLiveInstances(Long userId);
}