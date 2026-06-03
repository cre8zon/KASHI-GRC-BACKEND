package com.kashi.grc.workflow.enums;

/**
 * Controls how ACTOR task recipients are resolved when a workflow step becomes active.
 *
 * ROLE_BASED (default)
 *   All users holding the step's actorRoles in the tenant receive a task.
 *   Suitable for POOL-style steps where any qualified user can act first
 *   and the step advances on the first approval (ANY_ONE approval type).
 *   e.g. Org CISO review, Lead Auditor assignment, VRM acknowledge.
 *
 * ASSIGNMENT_SCOPED
 *   Actor recipients are resolved by a domain-specific WorkflowActorResolver
 *   bean registered for this workflow's entityType.
 *   Only users who were explicitly assigned work (section, item, control, question)
 *   in a prior step receive a task. Users with the right role but no assignment
 *   receive no task.
 *   Suitable for steps where work was pre-distributed in the prior step.
 *   e.g. Evidence Collection (only assigned auditees), Control Evaluation
 *   (only section-assigned auditors), Responder Fill (only assigned responders).
 *
 *   If the resolver returns an empty list (nobody assigned yet), the engine
 *   falls back to ROLE_BASED so the step is never permanently stuck.
 */
public enum ActorResolution {
    ROLE_BASED,
    ASSIGNMENT_SCOPED
}