package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.repository.AuditProjectInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AuditProjectEntityResolver — teaches the workflow engine how to resolve
 * artifactId and ownerId for AUDIT_PROJECT workflow instances.
 *
 * ── resolveArtifactId ────────────────────────────────────────────────────────
 * entityId IS the projectId — direct, no indirection.
 * Nav route: /audit/projects/:id → replaced with projectId.
 *
 * ── resolveOwnerId ───────────────────────────────────────────────────────────
 * Used when actor_resolution = ENTITY_OWNER on a project step.
 *
 * BUGFIX: previously queried AuditProjectRepository (the library TEMPLATE) using
 * instance.getEntityId() — but entityId is the AuditProjectInstance's id, not the
 * template's. This caused resolveOwnerId() to always return null (wrong repository,
 * wrong id space), silently falling through to ROLE_BASED fan-out for every
 * ORGANIZATION-side step. Fixed to query AuditProjectInstanceRepository and read
 * ownerIdSnapshot (set once at instance creation, isolated from template changes).
 *
 * Resolution by step side (current 12-step layout):
 *
 *   ORGANIZATION side (Steps 1, 2, 10, 11, 12):
 *     → projectInstance.ownerIdSnapshot (CAE / GRC Manager who owns the programme)
 *       Step 1  — Project Initiation: creator sets scope (ENTITY_CREATOR, not this path)
 *       Step 2  — Assign Lead Auditors: owner assigns a lead per engagement
 *       Step 10 — Cross-Framework Consolidation: owner reviews findings
 *       Step 11 — Management Response: owner responds
 *       Step 12 — Executive Sign-off: ROLE_BASED, not this path (CISO/ORG_OWNER pool)
 *
 *   AUDITOR side (Steps 3, 4, 8):
 *     → Uses ASSIGNMENT_SCOPED (scoped to each engagement's assigned lead auditor),
 *       not ENTITY_OWNER. resolver returns null here — engine uses the configured
 *       resolution for that step instead.
 *
 *   SYSTEM side (Steps 6, 9):
 *     → null — automated steps, no human owner.
 *
 * ── ISOLATION ─────────────────────────────────────────────────────────────────
 * Reads projectInstance.ownerIdSnapshot from AuditProjectInstance (frozen at
 * instance creation time — isolated from later changes to the library template).
 * Reads snapSide from StepInstance (snapshotted at step activation — isolated).
 * No live blueprint or template reads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditProjectEntityResolver implements WorkflowEntityResolver {

    private final AuditProjectInstanceRepository projectInstanceRepository;
    private final StepInstanceRepository         stepInstanceRepository;
    private final AuditEngagementRepository      engagementRepository;

    // No hardcoded step orders — routing is driven by snap_nav_key on the step instance.
    // Any step with navKey = "audit_engagement_detail" routes to the engagement page.
    // Any step with navKey = "audit_project_detail" routes to the project page.
    // Add new steps in the DB with the correct nav_key — no code change needed.

    @Override
    public String entityType() {
        return "AUDIT_PROJECT";
    }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        // Default: project page
        return instance.getEntityId();
    }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance, StepInstance stepInstance, Long assignedUserId) {
        if (stepInstance == null || stepInstance.getSnapNavKey() == null) {
            return resolveArtifactId(instance);
        }

        // Route by navKey — data-driven, no hardcoded step orders.
        // "audit_engagement_detail" → resolve to engagement page (steps 3-8 in WF16).
        // "audit_project_detail"    → resolve to project page (steps 1-2, 9-12).
        // Anything else             → fall back to project page.
        String navKey = stepInstance.getSnapNavKey();

        if ("audit_engagement_detail".equals(navKey)) {
            Long projectInstanceId = instance.getEntityId();

            // Match by assigned user (lead auditor) → their specific engagement
            if (assignedUserId != null) {
                return engagementRepository.findByProjectInstanceId(projectInstanceId)
                        .stream()
                        .filter(e -> assignedUserId.equals(e.getLeadAuditorId()))
                        .findFirst()
                        .map(e -> {
                            log.debug("[AUDIT-PROJ-RESOLVER] navKey={} userId={} → engagementId={}",
                                    navKey, assignedUserId, e.getId());
                            return e.getId();
                        })
                        .orElseGet(() -> {
                            // Auditee steps — match by auditeeAssignedUserId
                            return engagementRepository.findByProjectInstanceId(projectInstanceId)
                                    .stream().findFirst()
                                    .map(e -> e.getId())
                                    .orElse(instance.getEntityId());
                        });
            }

            // No assignedUserId — return first engagement as fallback
            return engagementRepository.findByProjectInstanceId(projectInstanceId)
                    .stream().findFirst().map(e -> e.getId())
                    .orElse(instance.getEntityId());
        }

        // Project-level steps: route to project page
        return instance.getEntityId();
    }

    @Override
    public Long resolveOwnerId(WorkflowInstance instance) {
        if (instance.getCurrentStepId() == null) {
            log.debug("[AUDIT-PROJ-RESOLVER] currentStepId null (terminal) — returning null");
            return null;
        }

        StepInstance si = stepInstanceRepository.findById(instance.getCurrentStepId()).orElse(null);
        if (si == null) {
            log.warn("[AUDIT-PROJ-RESOLVER] StepInstance not found id={}", instance.getCurrentStepId());
            return null;
        }

        String side = si.getSnapSide();

        return projectInstanceRepository.findById(instance.getEntityId())
                .map(projectInstance -> {
                    Long resolved;

                    if ("ORGANIZATION".equalsIgnoreCase(side)) {
                        // Steps 2, 10, 11 — project owner (CAE/CISO), set at instance creation
                        resolved = projectInstance.getOwnerIdSnapshot();
                        log.debug("[AUDIT-PROJ-RESOLVER] step='{}' ORGANIZATION → ownerIdSnapshot={}",
                                si.getSnapName(), resolved);
                    } else {
                        // AUDITOR → ASSIGNMENT_SCOPED/ROLE_BASED handles lead/section auditors
                        // SYSTEM  → automated, no owner
                        log.debug("[AUDIT-PROJ-RESOLVER] step='{}' side={} → null (handled elsewhere)",
                                si.getSnapName(), side);
                        resolved = null;
                    }

                    if (resolved == null) {
                        log.warn("[AUDIT-PROJ-RESOLVER] resolveOwnerId=null for step='{}' side={} " +
                                        "projectInstanceId={} — engine falls back to PREVIOUS_ACTOR then ROLE_BASED",
                                si.getSnapName(), side, instance.getEntityId());
                    }
                    return resolved;
                })
                .orElse(null);
    }
}