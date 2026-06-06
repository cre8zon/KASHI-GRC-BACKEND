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

    /**
     * All users holding the step's actorRoles in the tenant receive a task (pool behaviour).
     * Step advances when approval_type is satisfied across the pool.
     * e.g. "Any compliance manager can review this" — pool of all compliance managers.
     */
    ROLE_BASED,

    /**
     * Only users explicitly assigned work in a prior step receive a task.
     * Falls back to ROLE_BASED if resolver returns empty.
     * e.g. TPRM: only responders who were assigned questionnaire sections.
     */
    ASSIGNMENT_SCOPED,

    /**
     * Task goes only to the user who created/initiated the workflow (instance.initiatedBy).
     * Use for step 1 of any workflow where the creator IS the actor:
     *   - Issue triage: the GRC Manager who raised the issue triages it
     *   - Policy draft: the author who started the draft is the drafter
     *   - Vendor onboarding: the org user who onboarded the vendor fills step 1
     * Creates exactly ONE task — no pool, no fan-out.
     * Falls back to ROLE_BASED if initiatedBy is null.
     */
    ENTITY_CREATOR,

    /**
     * Task goes to the owner field on the entity (e.g. issue.ownerId, policy.ownerId).
     * Resolved via WorkflowEntityResolverRegistry.resolveOwnerId().
     * Use for steps where the assigned owner does the work:
     *   - Issue step 2+: the owner who was assigned during triage
     *   - Policy review: the policy owner
     * Creates exactly ONE task.
     * Falls back to PREVIOUS_ACTOR if owner is null, then to ROLE_BASED.
     */
    ENTITY_OWNER
}