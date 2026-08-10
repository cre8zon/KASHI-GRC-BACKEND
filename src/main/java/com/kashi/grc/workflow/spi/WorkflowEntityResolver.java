package com.kashi.grc.workflow.spi;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;

/**
 * Service Provider Interface — implement and register as a Spring @Component
 * to teach the workflow engine about a new domain entity type.
 *
 * The engine calls this at task-response-time to resolve the primary artifact
 * ID linked to a workflow instance. The frontend uses this ID to build the
 * route to the correct page (combined with the step's navKey).
 *
 * ── ADDING A NEW MODULE ───────────────────────────────────────────────────────
 * 1. Create a @Component implementing this interface in your module.
 * 2. Return your module's entityType() string.
 * 3. Implement resolveArtifactId() to return the correct artifact ID.
 *
 * ── STEP-AWARE ROUTING ────────────────────────────────────────────────────────
 * Some workflows route different steps to different entity pages.
 * Example: WF16 (Audit Project Lifecycle) governs an AuditProjectInstance,
 * but Steps 3-8 work on individual AuditEngagements.
 *
 * Override resolveArtifactId(instance, stepInstance, assignedUserId) to return
 * the correct artifact based on the active step and assigned user.
 * Default falls back through the chain: (instance, si, userId) → (instance, si) → (instance).
 */
public interface WorkflowEntityResolver {

    /** The entityType string this resolver handles (case-sensitive). */
    String entityType();

    /**
     * Basic resolution — returns the primary artifact ID for this workflow instance.
     * Used when no step context is available.
     */
    Long resolveArtifactId(WorkflowInstance instance);

    /**
     * Step-aware resolution — override when different steps route to different pages.
     * Default falls back to resolveArtifactId(instance).
     */
    default Long resolveArtifactId(WorkflowInstance instance, StepInstance stepInstance) {
        return resolveArtifactId(instance);
    }

    /**
     * Step-and-user-aware resolution — override when artifact depends on both
     * step AND which user owns the task (e.g. each lead auditor routes to their
     * own engagement, not just any engagement under the project).
     * Default falls back to resolveArtifactId(instance, stepInstance).
     */
    default Long resolveArtifactId(WorkflowInstance instance, StepInstance stepInstance, Long assignedUserId) {
        return resolveArtifactId(instance, stepInstance);
    }

    /**
     * Resolves the owner user ID for ENTITY_OWNER actor resolution.
     * Default returns null — override for entities with an owner field.
     */
    default Long resolveOwnerId(WorkflowInstance instance) {
        return null;
    }

    /**
     * Resolves a human-readable title for the entity.
     * Used by the task inbox for display without extra API calls.
     * Default returns null — override in module resolvers.
     */
    default String resolveEntityTitle(WorkflowInstance instance) {
        return null;
    }

    /**
     * Resolves titles for MANY instances at once, keyed by workflowInstanceId.
     *
     * WHY THIS EXISTS:
     *   The task inbox resolves a title per workflow instance. Doing that one at a
     *   time is a round trip each — a user holding 410 tasks across ~150 instances
     *   paid ~150 sequential queries, which was the whole of a 38s /my-tasks call.
     *
     * The default implementation just loops, so every existing resolver keeps
     * working unchanged and is no slower than before. Override it wherever the
     * lookup can be expressed as a single IN query.
     */
    default java.util.Map<Long, String> resolveEntityTitles(
            java.util.Collection<WorkflowInstance> instances) {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (WorkflowInstance wi : instances) {
            String title = resolveEntityTitle(wi);
            if (title != null) out.put(wi.getId(), title);
        }
        return out;
    }
}