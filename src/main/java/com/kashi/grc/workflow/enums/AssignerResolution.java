package com.kashi.grc.workflow.enums;

/**
 * Defines how a workflow step gets assigned when it becomes active.
 *
 * POOL
 *   All actor-role holders see the step in a shared queue.
 *   First to claim it gets the task. No push, no bottleneck.
 *   Best for: high-volume uniform work, any-reviewer pools, self-service queues.
 *
 * PUSH_TO_ROLES
 *   Tasks are pushed immediately to the step's assignerRole holders.
 *   They delegate/assign to the specific actor who will do the work.
 *   Assigner roles are stored in workflow_step_assigner_roles — can be any side.
 *   Best for: when a coordinator (different role/side) manages the handoff.
 *
 * PREVIOUS_ACTOR
 *   Whoever approved the previous step gets this assignment task.
 *   They have context from the previous step and pick the next actor.
 *   Best for: natural delegation chains (VRM picks CISO, CISO picks Responder).
 *
 * INITIATOR
 *   The user who started the workflow instance gets this assignment task.
 *   They drive every assignment centrally.
 *   Best for: workflows owned end-to-end by one person/role.
 */
public enum AssignerResolution {
    POOL,
    PUSH_TO_ROLES,
    PREVIOUS_ACTOR,
    INITIATOR
}