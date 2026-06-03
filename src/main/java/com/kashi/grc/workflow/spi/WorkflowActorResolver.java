package com.kashi.grc.workflow.spi;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;

import java.util.List;

/**
 * Service Provider Interface — implement and register as a Spring @Component
 * to provide assignment-scoped actor resolution for a specific entityType.
 *
 * The engine calls this inside assignTasksForStep() when the blueprint step
 * has actorResolution = ASSIGNMENT_SCOPED, instead of fanning out to all
 * role holders in the tenant.
 *
 * ── CONTRACT ─────────────────────────────────────────────────────────────────
 *
 * Return the IDs of users who were explicitly assigned work for this step
 * in the context of this workflow instance's entity (engagement, assessment, etc.).
 *
 * Return an EMPTY list if no assignments exist yet — the engine will fall back
 * to ROLE_BASED resolution so the step is never permanently stuck.
 *
 * ── ADDING A NEW MODULE ───────────────────────────────────────────────────────
 *
 * 1. Create a @Component implementing this interface in your module.
 * 2. Implement entityType() matching the workflow blueprint's entityType string.
 * 3. Implement resolveActorIds() to query your assignment tables.
 * 4. Mark the relevant blueprint steps actorResolution = ASSIGNMENT_SCOPED
 *    in the workflow admin UI.
 *
 * Zero changes to WorkflowEngineService or any other shared code.
 *
 * ── EXAMPLE ──────────────────────────────────────────────────────────────────
 *
 * {@code
 * @Component
 * public class AuditWorkflowActorResolver implements WorkflowActorResolver {
 *     @Override public String entityType() { return "AUDIT_ENGAGEMENT"; }
 *
 *     @Override
 *     public List<Long> resolveActorIds(WorkflowInstance instance, StepInstance si) {
 *         Long engagementId = instance.getEntityId();
 *         String side = si.getSnapSide();
 *         if ("AUDITEE".equalsIgnoreCase(side)) {
 *             return controlRepo.findDistinctAssignedAuditeeIdsByEngagementId(engagementId);
 *         } else if ("AUDITOR".equalsIgnoreCase(side)) {
 *             return sectionRepo.findDistinctAssignedAuditorIdsByEngagementId(engagementId);
 *         }
 *         return List.of(); // fall back to role-based for other sides
 *     }
 * }
 * }
 */
public interface WorkflowActorResolver {

    /**
     * The entityType this resolver handles.
     * Must match WorkflowInstance.entityType exactly (case-sensitive).
     * e.g. "AUDIT_ENGAGEMENT", "VENDOR", "RISK"
     */
    String entityType();

    /**
     * Returns the user IDs who should receive ACTOR tasks on this step.
     *
     * @param instance  the active workflow instance (gives you entityId, tenantId)
     * @param si        the step instance being activated (gives you snapSide, snapName, snapStepAction)
     * @return list of user IDs to create tasks for; empty = fall back to ROLE_BASED
     */
    List<Long> resolveActorIds(WorkflowInstance instance, StepInstance si);
}