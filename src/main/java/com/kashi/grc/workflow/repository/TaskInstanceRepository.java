package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * Count ACTOR tasks EXCLUDING a specific status.
     * Used by isStepApprovalSatisfied to exclude REJECTED sub-tasks (e.g. contributor
     * tasks closed by the responder when locking a section) from the approval denominator.
     * Without this, REJECTED contributor tasks inflate `total` and permanently stall
     * the ALL-approval gate even when all real responder tasks are approved.
     */
    long countByStepInstanceIdAndTaskRoleAndStatusNot(Long stepInstanceId, TaskRole taskRole, TaskStatus status);

    /**
     * Check if a specific user already has a PENDING task on a step instance.
     * Used by manual-assign to prevent duplicate tasks for the same user on the same step.
     */
    boolean existsByStepInstanceIdAndAssignedUserIdAndStatus(
            Long stepInstanceId, Long assignedUserId, TaskStatus status);

    /**
     * Check if the user has an active task on a specific workflow instance — single JOIN query.
     *
     * Replaces the N+1 pattern in AssessmentController.assertUserHasActiveTask():
     *   findByAssignedUserIdAndStatus(PENDING) + findByAssignedUserIdAndStatus(IN_PROGRESS)
     *   + N × stepInstanceRepository.findById(task.getStepInstanceId())
     *
     * All that is now a single EXISTS subquery — O(1) regardless of task count.
     * The statuses collection is typically [PENDING, IN_PROGRESS].
     */
    @Query("""
        SELECT COUNT(t) > 0 FROM TaskInstance t
        JOIN StepInstance s ON s.id = t.stepInstanceId
        WHERE t.assignedUserId      = :userId
          AND s.workflowInstanceId  = :workflowInstanceId
          AND t.status              IN :statuses
        """)
    boolean existsByUserIdAndWorkflowInstanceIdAndStatusIn(
            @Param("userId")             Long userId,
            @Param("workflowInstanceId") Long workflowInstanceId,
            @Param("statuses")           Collection<TaskStatus> statuses);

    /**
     * Check if the user has EVER had any task on a specific workflow instance — single JOIN query.
     *
     * Replaces the N+1 pattern in AssessmentController.assertUserHasParticipated():
     *   findByAssignedUserId(userId) + N × stepInstanceRepository.findById(task.getStepInstanceId())
     *
     * Used for read-only access guard on COMPLETED assessments — any historical participant
     * (regardless of task status) can view the completed data.
     */
    @Query("""
        SELECT COUNT(t) > 0 FROM TaskInstance t
        JOIN StepInstance s ON s.id = t.stepInstanceId
        WHERE t.assignedUserId     = :userId
          AND s.workflowInstanceId = :workflowInstanceId
        """)
    boolean existsByUserIdAndWorkflowInstanceId(
            @Param("userId")             Long userId,
            @Param("workflowInstanceId") Long workflowInstanceId);
}
