package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.event.SectionItemsNeededEvent;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AuditProjectEngagementItemRegistrar — registers AuditEngagement rows as
 * TaskSectionItems for workflow 16 (Audit Project Lifecycle) steps that need
 * per-engagement fan-out.
 *
 * Mirrors AuditSectionItemRegistrar exactly, but for the PROJECT level:
 *   AuditSectionItemRegistrar      → one item per AuditSectionInstance within ONE engagement
 *   ThisRegistrar                  → one item per AuditEngagement within ONE project
 *
 * ── WHY THIS EXISTS ──────────────────────────────────────────────────────────
 * Under a project, there is NO per-engagement workflow-14 instance — a single
 * workflow-16 instance (entityType=AUDIT_PROJECT, entityId=project.id) governs
 * every engagement created when the project was started (POST /projects/{id}/start).
 *
 * Steps 2 (Lead Auditor Onboarding), 3 (Evidence Owner Assignment),
 * 5 (Control Evaluation), 7 (Draft Report Review) each represent work that must
 * happen ONCE PER ENGAGEMENT before the step can complete. Configuring these
 * steps with tracksItems=true, itemRefType="AUDIT_ENGAGEMENT_INSTANCE" causes
 * this registrar to populate one TaskSectionItem per engagement under the
 * project — CompoundSectionRenderer then shows one row per engagement, each
 * linking to /module/audit_engagement/{id} (the standard engagement detail
 * drawer with Overview/Sections/Controls/Workflow/Findings tabs — identical
 * UI whether the engagement is standalone or under a project).
 *
 * ── COMPLETION SEMANTICS ─────────────────────────────────────────────────────
 * An item is marked complete by the LEAD AUDITOR of that specific engagement,
 * via the normal engagement-detail UI (e.g. assigning sections in Step 2,
 * recording test results in Step 5). The exact "what marks this item done"
 * action is step-specific and wired separately (e.g. assignSection() already
 * calls back into TaskSectionCompletionService for the corresponding step).
 * When ALL engagement-items for the step are complete, the parent compound
 * task auto-approves (existing CompoundTaskController behaviour, line 67) and
 * workflow 16 advances to the next step for the whole project.
 *
 * ── BACKWARD COMPATIBILITY ────────────────────────────────────────────────────
 * Only fires when:
 *   1. tracksItems = true
 *   2. itemRefType = "AUDIT_ENGAGEMENT_INSTANCE"
 * Workflow 14 (standalone SOC2) and other AUDIT_ENGAGEMENT_DETAIL-scoped
 * registrars (AuditSectionItemRegistrar, AuditControlSectionItemRegistrar) are
 * keyed on itemRefType=AUDIT_SECTION_INSTANCE / AUDIT_CONTROL_INSTANCE and are
 * completely unaffected by this listener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditProjectEngagementItemRegistrar {

    private static final String ITEM_REF_TYPE = "AUDIT_ENGAGEMENT_INSTANCE";

    private final WorkflowInstanceRepository   instanceRepository;
    private final AuditEngagementRepository    engagementRepository;
    private final TaskSectionCompletionService sectionService;

    @EventListener
    @Transactional
    public void onSectionItemsNeeded(SectionItemsNeededEvent event) {
        if (!ITEM_REF_TYPE.equalsIgnoreCase(event.itemRefType())) return;

        log.info("[PROJECT-ENG-REGISTRAR] Registering engagement items | " +
                        "workflowInstanceId={} | sectionKey={} | taskInstanceId={}",
                event.workflowInstanceId(), event.sectionKey(), event.taskInstanceId());

        WorkflowInstance wfInstance = instanceRepository.findById(event.workflowInstanceId())
                .orElse(null);

        if (wfInstance == null || !"AUDIT_PROJECT".equals(wfInstance.getEntityType())) {
            log.warn("[PROJECT-ENG-REGISTRAR] WorkflowInstance {} not found or not AUDIT_PROJECT — skipping",
                    event.workflowInstanceId());
            return;
        }

        Long projectInstanceId = wfInstance.getEntityId();
        Long assignedUserId    = event.assignedUserId();
        String sectionKey      = event.sectionKey();

        List<AuditEngagement> allEngagements = engagementRepository.findByProjectInstanceId(projectInstanceId);

        if (allEngagements.isEmpty()) {
            log.warn("[PROJECT-ENG-REGISTRAR] No engagements found for projectInstanceId={} — skipping",
                    projectInstanceId);
            return;
        }

        // Scope items to the task's assigned user's engagements:
        //
        // Step 2 (ENGAGEMENTS_LEAD_ASSIGNED): The ENTITY_OWNER (project creator) has
        // ONE task and is responsible for assigning ALL engagements — register all.
        //
        // Steps 3, 4, 9 (ENGAGEMENTS_ONBOARDED / EVIDENCE_OWNERS_ASSIGNED /
        // DRAFT_REPORTS_REVIEWED): Each lead auditor gets their own task scoped to
        // their engagements. Register only engagements where leadAuditorId matches
        // the task's assignedUserId — so Sneha's task tracks Sneha's engagements
        // and Rohit's task tracks Rohit's engagements independently.
        //
        // Fallback: if no engagements match the assigned user (e.g. lead auditors not
        // yet set, or single-lead project) register all engagements so the step is
        // not stuck with 0 items.
        List<AuditEngagement> engagements;
        boolean isEntityOwnerStep = "ENGAGEMENTS_LEAD_ASSIGNED".equalsIgnoreCase(sectionKey);

        if (isEntityOwnerStep || assignedUserId == null) {
            engagements = allEngagements;
        } else {
            engagements = allEngagements.stream()
                    .filter(e -> assignedUserId.equals(e.getLeadAuditorId()))
                    .toList();
            if (engagements.isEmpty()) {
                log.warn("[PROJECT-ENG-REGISTRAR] No engagements with leadAuditorId={} for projectInstanceId={} " +
                                "sectionKey={} — falling back to all engagements",
                        assignedUserId, projectInstanceId, sectionKey);
                engagements = allEngagements;
            }
        }

        log.info("[PROJECT-ENG-REGISTRAR] Scoped to {} of {} engagement(s) | assignedUserId={} | sectionKey={}",
                engagements.size(), allEngagements.size(), assignedUserId, sectionKey);

        List<TaskSectionCompletionService.ItemRegistration> registrations = engagements.stream()
                .map(e -> new TaskSectionCompletionService.ItemRegistration(
                        ITEM_REF_TYPE,
                        e.getId(),
                        buildLabel(e)
                ))
                .toList();

        sectionService.registerItems(
                event.taskInstanceId(),
                event.sectionKey(),
                registrations
        );

        log.info("[PROJECT-ENG-REGISTRAR] Registered {} engagement items | projectId={} | " +
                        "sectionKey={} | taskInstanceId={}",
                registrations.size(), projectInstanceId, event.sectionKey(), event.taskInstanceId());
    }

    private String buildLabel(AuditEngagement e) {
        String ref  = e.getEngagementRef() != null ? e.getEngagementRef() : ("ENG-" + e.getId());
        String name = e.getName() != null ? e.getName() : ref;
        return truncate(ref + " — " + name, 200);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }
}