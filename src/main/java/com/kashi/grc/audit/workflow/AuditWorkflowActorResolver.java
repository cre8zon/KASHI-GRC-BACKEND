package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves ACTOR task recipients for AUDIT_ENGAGEMENT workflow steps
 * that have actorResolution = ASSIGNMENT_SCOPED.
 *
 * ── AUDITEE side steps (e.g. Step 4 — Evidence Collection) ───────────────────
 * Returns distinct auditee user IDs who have ≥1 control assigned to them in
 * this engagement (audit_control_instances.auditee_assigned_user_id).
 * Only users Sneha explicitly assigned in Step 3 receive tasks.
 *
 * ── AUDITOR side steps (e.g. Step 5 — Control Evaluation) ────────────────────
 * Returns distinct auditor user IDs who have ≥1 section assigned to them in
 * this engagement (audit_section_instances.assigned_auditor_id).
 * Only auditors assigned sections in Step 2 receive tasks.
 *
 * ── All other sides ───────────────────────────────────────────────────────────
 * Returns empty list → engine falls back to ROLE_BASED.
 * LEAD_AUDITOR steps, ORGANIZATION steps, SYSTEM steps are not assignment-scoped.
 *
 * ── Safety ───────────────────────────────────────────────────────────────────
 * If nobody has been assigned yet, returns empty list → engine falls back to
 * ROLE_BASED fan-out so the step is never permanently stuck.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWorkflowActorResolver implements WorkflowActorResolver {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final AuditSectionInstanceRepository sectionInstanceRepository;

    @Override
    public String entityType() {
        return "AUDIT_ENGAGEMENT";
    }

    @Override
    public List<Long> resolveActorIds(WorkflowInstance instance, StepInstance si) {
        Long engagementId = resolveEngagementId(instance);
        if (engagementId == null) {
            log.warn("[AUDIT-ACTOR-RESOLVER] No engagement found for workflowInstanceId={}", instance.getId());
            return List.of();
        }

        String side = si.getSnapSide();
        if (side == null) return List.of();

        return switch (side.toUpperCase()) {
            case "AUDITEE" -> {
                List<Long> ids = controlInstanceRepository
                        .findDistinctAssignedAuditeeIdsByEngagementId(engagementId);
                if (ids.isEmpty()) {
                    // Section-level "Assign auditee" (with cascade) deliberately does not
                    // write down to controls — fall back to section-level assignment,
                    // mirroring AuditProjectWorkflowActorResolver's AUDITEE case.
                    ids = sectionInstanceRepository
                            .findDistinctAssignedAuditeeIdsByEngagementId(engagementId);
                }
                log.info("[AUDIT-ACTOR-RESOLVER] AUDITEE step '{}' | engagementId={} | {} assigned auditee(s) (control-level, falls back to section-level)",
                        si.getSnapName(), engagementId, ids.size());
                yield ids;
            }
            case "AUDITOR" -> {
                List<Long> ids = sectionInstanceRepository
                        .findDistinctAssignedAuditorIdsByEngagementId(engagementId);
                log.info("[AUDIT-ACTOR-RESOLVER] AUDITOR step '{}' | engagementId={} | {} assigned auditor(s)",
                        si.getSnapName(), engagementId, ids.size());
                yield ids;
            }
            default -> {
                log.debug("[AUDIT-ACTOR-RESOLVER] side='{}' not assignment-scoped — returning empty (ROLE_BASED fallback)",
                        side);
                yield List.of();
            }
        };
    }

    private Long resolveEngagementId(WorkflowInstance instance) {
        return engagementRepository
                .findByTenantIdAndWorkflowInstanceId(instance.getTenantId(), instance.getId())
                .map(e -> e.getId())
                .orElse(null);
    }
}