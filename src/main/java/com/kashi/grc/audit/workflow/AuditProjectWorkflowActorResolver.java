package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves ACTOR task recipients for AUDIT_PROJECT (Workflow 16) steps with
 * actorResolution = ASSIGNMENT_SCOPED.
 *
 * THE RULE (applies at every level of the project hierarchy):
 *   Once an assignment record already exists for an entity, the NEXT action on
 *   that entity belongs exclusively to whoever was assigned — never the whole
 *   role pool. ROLE_BASED is only correct at the first hop in a chain, where no
 *   assignment exists yet and *someone* with the role must be the one to create it.
 *
 * Owner assigns Lead Auditors to engagements          → first hop: ROLE_BASED (Step 2)
 *   Lead Auditor assigns Sections to Section Auditors  → scoped to assigned Lead Auditor (Step 3)
 *     Lead Auditor assigns Evidence Owners to Sections → scoped to assigned Lead Auditor (Step 4)
 *       Section Auditee submits Evidence               → scoped to assigned Section Auditee (Step 5)
 *   Lead Auditor reviews Draft Report                  → scoped to assigned Lead Auditor (Step 8)
 *
 * Each engagement under the project carries its own leadAuditorId (set in Step 2).
 * "Section Auditor"/"Section Auditee" assignment is per audit_section_instance
 * within each engagement (set in Steps 3/4 respectively).
 *
 * Distinguishing which scope a step needs can't be done from `side` alone — Steps
 * 3, 4, and 8 are all side=AUDITOR but need DIFFERENT scopes (lead auditor for all
 * three, since no section-level auditor exists yet at Steps 3/4, and Step 8 is a
 * report-review step that stays with whoever led the engagement throughout). We key
 * off the step's snapshot name to pick the right scope — using a stable substring
 * match rather than a step_id constant, so this keeps working if step IDs change
 * across environments/reseeds.
 *
 * ── Safety ───────────────────────────────────────────────────────────────────
 * If nobody has been assigned yet, returns empty list → engine falls back to
 * ROLE_BASED fan-out so the step is never permanently stuck.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditProjectWorkflowActorResolver implements WorkflowActorResolver {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final AuditSectionInstanceRepository sectionInstanceRepository;

    @Override
    public String entityType() {
        return "AUDIT_PROJECT";
    }

    @Override
    public List<Long> resolveActorIds(WorkflowInstance instance, StepInstance si) {
        Long projectInstanceId = instance.getEntityId();
        List<AuditEngagement> engagements = engagementRepository.findByProjectInstanceId(projectInstanceId);

        if (engagements.isEmpty()) {
            log.warn("[AUDIT-PROJECT-ACTOR-RESOLVER] No engagements found for projectInstanceId={}",
                    projectInstanceId);
            return List.of();
        }

        String stepName = si.getSnapName() != null ? si.getSnapName().toLowerCase() : "";
        String side     = si.getSnapSide();
        Set<Long> userIds = new LinkedHashSet<>();

        // ── Scope: engagement's assigned Lead Auditor ──────────────────────────
        // Steps that operate at the lead-auditor level — the lead has already been
        // assigned (Step 2) by the time these run, so the task belongs only to them.
        boolean isLeadAuditorScoped =
                stepName.contains("assign sections to auditors")
                        || stepName.contains("assign evidence owners")
                        || stepName.contains("draft report review");

        if (isLeadAuditorScoped) {
            for (AuditEngagement eng : engagements) {
                if (eng.getLeadAuditorId() != null) userIds.add(eng.getLeadAuditorId());
            }
            log.info("[AUDIT-PROJECT-ACTOR-RESOLVER] Lead-auditor-scoped step '{}' | projectInstanceId={} | " +
                            "{} engagement(s) | {} distinct lead auditor(s)",
                    si.getSnapName(), projectInstanceId, engagements.size(), userIds.size());
            return List.copyOf(userIds);
        }

        // ── Scope: section's assigned auditee (e.g. Evidence Submission) ───────
        // Falls back to section-level assignment when no controls have been
        // individually assigned yet — this is the COMMON case: Step 4 only
        // assigns auditees at the section level (cascadeToChildren defaults to
        // false), and individual control assignment happens later, separately,
        // by the section auditee themselves. Without this fallback, the resolver
        // finds zero control-level assignments and the engine falls through to
        // ROLE_BASED fan-out — which is exactly the bug this branch fixes.
        if ("AUDITEE".equalsIgnoreCase(side)) {
            for (AuditEngagement eng : engagements) {
                List<Long> controlLevel = controlInstanceRepository
                        .findDistinctAssignedAuditeeIdsByEngagementId(eng.getId());
                if (!controlLevel.isEmpty()) {
                    userIds.addAll(controlLevel);
                } else {
                    userIds.addAll(sectionInstanceRepository
                            .findDistinctAssignedAuditeeIdsByEngagementId(eng.getId()));
                }
            }
            log.info("[AUDIT-PROJECT-ACTOR-RESOLVER] AUDITEE-scoped step '{}' | projectInstanceId={} | " +
                            "{} engagement(s) | {} distinct assigned auditee(s) (control-level, falls back to section-level)",
                    si.getSnapName(), projectInstanceId, engagements.size(), userIds.size());
            return List.copyOf(userIds);
        }

        // ── Scope: section's assigned auditor (any future per-section AUDITOR step) ──
        if ("AUDITOR".equalsIgnoreCase(side)) {
            for (AuditEngagement eng : engagements) {
                userIds.addAll(sectionInstanceRepository
                        .findDistinctAssignedAuditorIdsByEngagementId(eng.getId()));
            }
            log.info("[AUDIT-PROJECT-ACTOR-RESOLVER] AUDITOR-scoped (section) step '{}' | projectInstanceId={} | " +
                            "{} engagement(s) | {} distinct assigned auditor(s)",
                    si.getSnapName(), projectInstanceId, engagements.size(), userIds.size());
            return List.copyOf(userIds);
        }

        log.debug("[AUDIT-PROJECT-ACTOR-RESOLVER] step '{}' side='{}' not assignment-scoped — " +
                "returning empty (ROLE_BASED fallback)", si.getSnapName(), side);
        return List.of();
    }
}