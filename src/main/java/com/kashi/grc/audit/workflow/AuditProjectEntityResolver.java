package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.repository.AuditProjectRepository;
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
 * Resolution by step side:
 *
 *   ORGANIZATION side (Steps 1, 4, 5):
 *     → project.ownerId (CAE / GRC Manager who owns the programme)
 *       Step 1 — Project Initiation: creator sets scope
 *       Step 4 — Consolidation: project owner reviews cross-framework findings
 *       Step 5 — Executive Sign-off: CISO/CAE closes programme
 *
 *   AUDITOR side (Step 2 — Engagement Activation):
 *     → Uses ROLE_BASED not ENTITY_OWNER (multiple lead auditors per project)
 *       Each lead auditor activates their own engagement.
 *       resolver returns null here — engine uses ROLE_BASED.
 *
 *   SYSTEM side (Step 3 — Fieldwork Monitoring):
 *     → null — automated step, no human owner.
 *
 * ── ISOLATION ─────────────────────────────────────────────────────────────────
 * Reads project.ownerId from AuditProject domain entity (set at creation time).
 * Reads snapSide from StepInstance (snapshotted at step activation — isolated).
 * No live blueprint reads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditProjectEntityResolver implements WorkflowEntityResolver {

    private final AuditProjectRepository  projectRepository;
    private final StepInstanceRepository  stepInstanceRepository;

    @Override
    public String entityType() {
        return "AUDIT_PROJECT";
    }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        Long artifactId = instance.getEntityId();
        log.debug("[AUDIT-PROJ-RESOLVER] entityId={} → artifactId={}", instance.getEntityId(), artifactId);
        return artifactId;
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

        return projectRepository.findById(instance.getEntityId())
                .map(project -> {
                    Long resolved;

                    if ("ORGANIZATION".equalsIgnoreCase(side)) {
                        // Steps 1, 4, 5 — project owner (CAE/CISO)
                        resolved = project.getOwnerId();
                        log.debug("[AUDIT-PROJ-RESOLVER] step='{}' ORGANIZATION → ownerId={}",
                                si.getSnapName(), resolved);
                    } else {
                        // AUDITOR → ROLE_BASED handles lead auditors
                        // SYSTEM  → automated, no owner
                        log.debug("[AUDIT-PROJ-RESOLVER] step='{}' side={} → null (ROLE_BASED or SYSTEM)",
                                si.getSnapName(), side);
                        resolved = null;
                    }

                    if (resolved == null) {
                        log.warn("[AUDIT-PROJ-RESOLVER] resolveOwnerId=null for step='{}' side={} projectId={}" +
                                        " — engine falls back to PREVIOUS_ACTOR then ROLE_BASED",
                                si.getSnapName(), side, instance.getEntityId());
                    }
                    return resolved;
                })
                .orElse(null);
    }
}