package com.kashi.grc.workflow.spi;

import com.kashi.grc.workflow.domain.WorkflowInstance;

/**
 * Service Provider Interface — implement and register as a Spring @Component
 * to teach the workflow engine about a new domain entity type.
 *
 * The engine calls this at task-response-time to resolve the primary artifact
 * ID linked to a workflow instance. The frontend uses this ID to build the
 * route to the correct page.
 *
 * ── ADDING A NEW MODULE ───────────────────────────────────────────────────────
 *
 * 1. Create a @Component implementing this interface in your module.
 * 2. Return your module's entityType() string (must match what the workflow
 *    blueprint uses as entityType, e.g. "AUDIT", "RISK", "POLICY").
 * 3. Implement resolveArtifactId() to look up your artifact from the instance.
 * 4. Add entries to the frontend WORKFLOW_ROUTES config for your entityType.
 *
 * Zero changes to WorkflowEngineService, TaskInbox, or any other shared code.
 *
 * ── EXAMPLE ──────────────────────────────────────────────────────────────────
 *
 * {@code
 * @Component
 * public class AuditEntityResolver implements WorkflowEntityResolver {
 *     @Override public String entityType() { return "AUDIT"; }
 *
 *     @Override
 *     public Long resolveArtifactId(WorkflowInstance instance) {
 *         return engagementRepository
 *             .findByWorkflowInstanceId(instance.getId())
 *             .map(AuditEngagement::getId)
 *             .orElse(null);
 *     }
 * }
 * }
 */
public interface WorkflowEntityResolver {

    /**
     * The entityType string this resolver handles.
     * Must match WorkflowInstance.entityType exactly (case-sensitive).
     * e.g. "VENDOR", "AUDIT", "RISK", "POLICY", "CONTRACT"
     */
    String entityType();

    /**
     * Given a workflow instance, returns the primary artifact ID for this
     * entity type. Returns null if no artifact exists yet (e.g. automated
     * action hasn't fired yet, or this step doesn't have an artifact).
     *
     * @param instance the active workflow instance
     * @return artifact ID (e.g. assessmentId, engagementId) or null
     */
    Long resolveArtifactId(WorkflowInstance instance);
}