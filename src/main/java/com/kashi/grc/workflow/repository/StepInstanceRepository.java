package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.enums.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for StepInstance entities.
 *
 * ── ADDITIONS ────────────────────────────────────────────────────────────────
 *
 * findByWorkflowInstanceIdAndStepId
 *   Used by send-back handler for iteration counting.
 *
 * findAllUnassigned
 *   Used by StepSlaMonitor to find UNASSIGNED steps so the monitor can
 *   re-attempt role resolution after a delay (role membership may have changed).
 *
 * findAllSlaBreached
 *   Used by StepSlaMonitor to detect IN_PROGRESS steps that have exceeded their
 *   SLA window and have no approved actor tasks yet. Does not re-query already
 *   SLA_BREACHED steps (they were already handled).
 *
 * findStuckSteps
 *   Ops/admin query — returns IN_PROGRESS steps with zero PENDING or APPROVED
 *   actor tasks. Used by the admin health endpoint and runbook monitoring.
 */
@Repository
public interface StepInstanceRepository extends JpaRepository<StepInstance, Long> {

    // ── Pre-existing methods (unchanged) ─────────────────────────────────────

    List<StepInstance> findByWorkflowInstanceIdOrderByCreatedAtAsc(Long workflowInstanceId);

    List<StepInstance> findByWorkflowInstanceId(Long workflowInstanceId);

    List<StepInstance> findByWorkflowInstanceIdAndStatus(Long workflowInstanceId, StepStatus status);

    List<StepInstance> findByStepIdAndStatus(Long stepId, StepStatus status);

    long countByWorkflowInstanceIdAndStatus(Long workflowInstanceId, StepStatus status);

    /**
     * Returns all StepInstances for a given workflow instance + step definition.
     * Used to count how many times a step has been visited (send-back loop detection).
     */
    List<StepInstance> findByWorkflowInstanceIdAndStepId(Long workflowInstanceId, Long stepId);

    // ── New: UNASSIGNED steps ─────────────────────────────────────────────────

    /**
     * Returns all UNASSIGNED step instances across all tenants.
     * StepSlaMonitor calls this on each tick to re-attempt role resolution —
     * a user may have been added to the required role since the step was created.
     */
    List<StepInstance> findByStatus(StepStatus status);

    // ── New: SLA breach detection ─────────────────────────────────────────────

    /**
     * Returns IN_PROGRESS step instances whose SLA deadline has passed.
     * StepSlaMonitor calls this to find steps that need escalation.
     *
     * Only IN_PROGRESS is checked — UNASSIGNED steps are handled separately.
     * Already-SLA_BREACHED steps are excluded to avoid double-notification.
     */
    @Query("SELECT si FROM StepInstance si " +
            "WHERE si.status = 'IN_PROGRESS' " +
            "AND si.slaDueAt IS NOT NULL " +
            "AND si.slaDueAt < :now " +
            "AND si.completedAt IS NULL")
    List<StepInstance> findAllSlaBreached(@Param("now") LocalDateTime now);

    // ── New: ops/admin stuck-step detection ──────────────────────────────────

    /**
     * Returns IN_PROGRESS step instances that have no PENDING or APPROVED actor tasks.
     * These are "stuck" — the step is active but nobody can act on it.
     *
     * Used by:
     *   - Admin health endpoint GET /admin/workflow/stuck-steps
     *   - StepSlaMonitor (belt-and-suspenders sweep alongside the UNASSIGNED check)
     *
     * SQL equivalent:
     *   SELECT si.* FROM step_instances si
     *   WHERE si.status = 'IN_PROGRESS'
     *   AND NOT EXISTS (
     *     SELECT 1 FROM task_instances ti
     *     WHERE ti.step_instance_id = si.id
     *       AND ti.task_role = 'ACTOR'
     *       AND ti.status IN ('PENDING', 'APPROVED')
     *   )
     */
    @Query("SELECT si FROM StepInstance si " +
            "WHERE si.status = 'IN_PROGRESS' " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM TaskInstance ti " +
            "  WHERE ti.stepInstanceId = si.id " +
            "  AND ti.taskRole = com.kashi.grc.workflow.enums.TaskRole.ACTOR " +
            "  AND ti.status IN (" +
            "    com.kashi.grc.workflow.enums.TaskStatus.PENDING, " +
            "    com.kashi.grc.workflow.enums.TaskStatus.APPROVED" +
            "  )" +
            ")")
    List<StepInstance> findStuckSteps();
}