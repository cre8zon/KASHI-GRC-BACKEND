package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AuditEngagementEntityResolver — resolves artifactId and ownerId for
 * AUDIT_ENGAGEMENT workflow instances.
 *
 * ── resolveArtifactId ────────────────────────────────────────────────────────
 * entityId IS the engagementId — direct, no indirection.
 *
 * ── resolveOwnerId ───────────────────────────────────────────────────────────
 * Called by the engine BEFORE creating tasks for a step (actor_resolution=ENTITY_OWNER).
 * Returns the userId who should receive the ACTOR task for this step.
 *
 * Resolution by snapSide of the current step instance:
 *
 *   AUDITOR      → engagement.leadAuditorId
 *     Steps 2, 3, 7 — the lead auditor selected at engagement creation time
 *     does all auditor-side work (section assignment, evidence owner assignment,
 *     draft report review). This is why picking a lead auditor on the form matters.
 *
 *   ORGANIZATION → engagement.ownerId
 *     Steps 1, 6, 9, 10 — the engagement owner (CAE/CISO/GRC Manager) handles
 *     org-side steps (setup, findings remediation, management response, sign-off).
 *
 *   AUDITEE      → null
 *     Step 4 — evidence collection. Auditee tasks are ASSIGNMENT_SCOPED per
 *     control, not a single person. Engine falls back to ROLE_BASED.
 *
 *   SYSTEM/null  → null
 *     Step 8 — automated. No owner needed.
 *
 * ── WHY NOT READ TASKS ───────────────────────────────────────────────────────
 * resolveOwnerId() is called BEFORE tasks are created for the step — reading
 * PENDING tasks would always return empty. Must read from the engagement domain
 * object directly.
 *
 * ── FALLBACK ─────────────────────────────────────────────────────────────────
 * Returns null → engine tries PREVIOUS_ACTOR then ROLE_BASED. No hard failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEngagementEntityResolver implements WorkflowEntityResolver {

    private final AuditEngagementRepository engagementRepository;
    private final StepInstanceRepository    stepInstanceRepository;

    @Override
    public String entityType() {
        return "AUDIT_ENGAGEMENT";
    }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        Long artifactId = instance.getEntityId();
        log.debug("[AUDIT-ENG-RESOLVER] entityId={} → artifactId={}", instance.getEntityId(), artifactId);
        return artifactId;
    }

    @Override
    public Long resolveOwnerId(WorkflowInstance instance) {
        if (instance.getCurrentStepId() == null) {
            log.debug("[AUDIT-ENG-RESOLVER] currentStepId null (terminal) — returning null");
            return null;
        }

        // Read the current step's snapSide — snapshotted at activation, never changes
        StepInstance si = stepInstanceRepository.findById(instance.getCurrentStepId())
                .orElse(null);
        if (si == null) {
            log.warn("[AUDIT-ENG-RESOLVER] StepInstance not found id={}", instance.getCurrentStepId());
            return null;
        }

        String side = si.getSnapSide();

        return engagementRepository.findById(instance.getEntityId())
                .map(engagement -> {
                    Long resolved;

                    if ("AUDITOR".equalsIgnoreCase(side)) {
                        resolved = engagement.getLeadAuditorId();
                        log.debug("[AUDIT-ENG-RESOLVER] step='{}' AUDITOR side → leadAuditorId={}",
                                si.getSnapName(), resolved);

                    } else if ("ORGANIZATION".equalsIgnoreCase(side)) {
                        resolved = engagement.getOwnerId();
                        log.debug("[AUDIT-ENG-RESOLVER] step='{}' ORGANIZATION side → ownerId={}",
                                si.getSnapName(), resolved);

                    } else {
                        // AUDITEE → ASSIGNMENT_SCOPED handles per-control
                        // SYSTEM  → automated, no owner
                        log.debug("[AUDIT-ENG-RESOLVER] step='{}' side={} → null (fallback)",
                                si.getSnapName(), side);
                        resolved = null;
                    }

                    if (resolved == null) {
                        log.warn("[AUDIT-ENG-RESOLVER] resolveOwnerId=null for step='{}' side={} " +
                                        "engagementId={} — check leadAuditorId/ownerId set on engagement",
                                si.getSnapName(), side, instance.getEntityId());
                    }
                    return resolved;
                })
                .orElse(null);
    }
}