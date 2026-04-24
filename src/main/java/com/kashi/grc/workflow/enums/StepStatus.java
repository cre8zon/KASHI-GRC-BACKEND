package com.kashi.grc.workflow.enums;

/**
 * Lifecycle states for a StepInstance.
 *
 * AWAITING_ASSIGNMENT — step created, no tasks fanned out yet.
 *   Set by createStepInstance() before assignTasksForStep() runs.
 *   Normal intermediate state — lasts milliseconds in the happy path.
 *
 * UNASSIGNED — NEW.
 *   Set by assignTasksForStep() when actorRoles exist in the blueprint
 *   but ZERO users hold any of those roles in this tenant at activation time.
 *   The step is "alive" but has no one to do the work. The workflow initiator
 *   is notified immediately so they can add users to the role or manually assign.
 *   Transitions to IN_PROGRESS when a task is manually created via
 *   POST /workflow-instances/{instanceId}/steps/{stepInstanceId}/manual-assign.
 *
 *   DB migration: add 'UNASSIGNED' to step_instances.status CHECK constraint if one exists.
 *
 * IN_PROGRESS — tasks exist and are being worked.
 *
 * APPROVED — all required approvals satisfied, step complete.
 *
 * REJECTED — step rejected; triggers workflow-level rejection.
 *
 * SKIPPED — step was optional and skipped without action.
 *
 * REASSIGNED — step was sent back to a previous step.
 *   The current step instance is closed; a new StepInstance is created for the target step.
 *
 * SLA_BREACHED — NEW.
 *   Set by StepSlaMonitor when a step has been IN_PROGRESS or UNASSIGNED past its sla_due_at
 *   and still has no approved actor tasks. The step remains actionable — the monitor
 *   records the breach and escalates, but does not block further progress.
 *   Transitions to IN_PROGRESS / APPROVED as normal once action is taken.
 */
public enum StepStatus {

    /**
     * Step is created but no tasks assigned yet.
     * Waiting for assignTasksForStep to fan out tasks.
     * Normal intermediate state — transitions to IN_PROGRESS within the same request.
     */
    AWAITING_ASSIGNMENT,

    /**
     * Step has actorRoles configured but ZERO users hold those roles in this tenant.
     * The step cannot proceed without manual intervention.
     * The workflow initiator receives a STEP_UNASSIGNED notification.
     * Transitions to IN_PROGRESS via manual-assign endpoint.
     */
    UNASSIGNED,

    /** Step is active — tasks exist and are being worked. */
    IN_PROGRESS,

    /** Step completed successfully — all required approvals satisfied. */
    APPROVED,

    /** Step rejected — triggers workflow-level rejection. */
    REJECTED,

    /** Step was optional and skipped without action. */
    SKIPPED,

    /**
     * Step was sent back to a previous step.
     * The current step instance is closed; a new StepInstance is created for the target step.
     */
    REASSIGNED,

    /**
     * Step has exceeded its configured sla_hours without being approved.
     * Recorded by StepSlaMonitor. Step remains actionable.
     * Initiator and assignerRole holders are notified to escalate.
     */
    SLA_BREACHED
}