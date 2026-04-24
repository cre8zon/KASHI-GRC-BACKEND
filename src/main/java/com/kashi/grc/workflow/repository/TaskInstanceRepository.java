package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskInstanceRepository extends JpaRepository<TaskInstance, Long> {

    List<TaskInstance> findByStepInstanceId(Long stepInstanceId);

    List<TaskInstance> findByStepInstanceIdAndStatus(Long stepInstanceId, TaskStatus status);

    List<TaskInstance> findByAssignedUserIdAndStatus(Long assignedUserId, TaskStatus status);

    List<TaskInstance> findByAssignedUserId(Long assignedUserId);

    long countByStepInstanceId(Long stepInstanceId);

    long countByStepInstanceIdAndStatus(Long stepInstanceId, TaskStatus status);

    /** Used by cancelInstance to expire ALL non-terminal tasks across multiple step instances */
    List<TaskInstance> findByStepInstanceIdIn(List<Long> stepInstanceIds);

    /** Used by cancelInstance to expire only PENDING tasks (kept for backward compat) */
    List<TaskInstance> findByStepInstanceIdInAndStatus(List<Long> stepInstanceIds, TaskStatus status);

    Optional<TaskInstance> findByIdAndAssignedUserId(Long id, Long assignedUserId);

    /**
     * Count only ACTOR tasks on a step instance — used by isStepApprovalSatisfied.
     * ASSIGNER tasks are excluded so approving/delegating from an assigner role
     * does not accidentally satisfy the approval condition and advance the workflow.
     */
    long countByStepInstanceIdAndTaskRole(Long stepInstanceId, TaskRole taskRole);

    /**
     * Count ACTOR tasks with a specific status — used by isStepApprovalSatisfied.
     * Paired with countByStepInstanceIdAndTaskRole to compute the approval fraction.
     */
    long countByStepInstanceIdAndTaskRoleAndStatus(Long stepInstanceId, TaskRole taskRole, TaskStatus status);

    /**
     * Check if a specific user already has a PENDING task on a step instance.
     * Used by manual-assign to prevent duplicate tasks for the same user on the same step.
     */
    boolean existsByStepInstanceIdAndAssignedUserIdAndStatus(
            Long stepInstanceId, Long assignedUserId, TaskStatus status);
}