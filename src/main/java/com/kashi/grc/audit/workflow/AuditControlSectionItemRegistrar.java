package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
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
 * Registers AuditControlInstance rows as TaskSectionItems when a workflow step
 * that has tracksItems=true and itemRefType="AUDIT_CONTROL_INSTANCE" activates.
 *
 * Mirrors AssessmentSectionItemRegistrar (QUESTION_RESPONSE) exactly.
 *
 * ── HOW IT CONNECTS ──────────────────────────────────────────────────────────
 * 1. Engagement workflow Step 4 (Evidence Collection) activates.
 * 2. assignTasksForStep() → snapshotSectionsForTask() creates TaskSectionCompletion
 *    for section with sectionKey=EVIDENCE_UPLOADED, tracksItems=true,
 *    itemRefType=AUDIT_CONTROL_INSTANCE.
 * 3. snapshotSectionsForTask() fires SectionItemsNeededEvent.
 * 4. THIS LISTENER catches it (itemRefType matches), loads all AuditControlInstance
 *    rows for the engagement, and calls sectionService.registerItems().
 * 5. CompoundSectionRenderer now has one item row per control — each can be
 *    marked complete when the auditee uploads evidence and calls onSectionEvent().
 *
 * ── HOW ENGAGEMENT IS FOUND ──────────────────────────────────────────────────
 * AuditEngagementRepository.findByTenantIdAndWorkflowInstanceId() resolves the
 * engagement from the workflowInstanceId on the event.
 * This FK is set by AuditEngagementService.startWorkflowIfConfigured().
 *
 * ── BACKWARD COMPATIBILITY ────────────────────────────────────────────────────
 * Only fires when a blueprint section has:
 *   1. tracksItems = true
 *   2. itemRefType = "AUDIT_CONTROL_INSTANCE"
 * Existing TPRM blueprints are unaffected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditControlSectionItemRegistrar {

    private static final String ITEM_REF_TYPE = "AUDIT_CONTROL_INSTANCE";

    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final AuditSectionInstanceRepository sectionInstanceRepository;
    private final TaskSectionCompletionService   sectionService;
    private final WorkflowInstanceRepository     workflowInstanceRepository;

    @EventListener
    @Transactional
    public void onSectionItemsNeeded(SectionItemsNeededEvent event) {
        // Only handle AUDIT_CONTROL_INSTANCE sections
        if (!ITEM_REF_TYPE.equalsIgnoreCase(event.itemRefType())) return;

        log.info("[AUDIT-CTRL-REGISTRAR] Registering control items | " +
                        "workflowInstanceId={} | sectionKey={} | taskInstanceId={}",
                event.workflowInstanceId(), event.sectionKey(), event.taskInstanceId());

        // Resolve engagement via WorkflowInstance.entityId — set at instance-creation
        // time, so no race window if this fires synchronously during engagement
        // creation (same fix as AuditSectionItemRegistrar; see that class for detail).
        WorkflowInstance wfInstance = workflowInstanceRepository
                .findById(event.workflowInstanceId())
                .orElse(null);

        var engagement = (wfInstance != null && "AUDIT_ENGAGEMENT".equals(wfInstance.getEntityType()))
                ? engagementRepository.findById(wfInstance.getEntityId()).orElse(null)
                : null;

        if (engagement == null) {
            log.warn("[AUDIT-CTRL-REGISTRAR] No engagement found for workflowInstanceId={} tenantId={}" +
                            " — WorkflowInstance missing or not AUDIT_ENGAGEMENT",
                    event.workflowInstanceId(), event.tenantId());
            return;
        }

        // Load control instances scoped to this specific task's assigned user.
        // The field used depends on which side is doing the work:
        //   EVIDENCE_UPLOADED  (Step 4, auditee side) → auditeeAssignedUserId
        //   CONTROLS_EVALUATED (Step 5, auditor side) → assignedAuditorId
        //                       (after cascade-fix, controls inherit section auditor
        //                        via section path lookup as fallback)
        Long assignedUserId = event.assignedUserId();
        String sectionKey   = event.sectionKey();

        // Every control whose EFFECTIVE owner on this side is the task's user:
        // its own assignee when set, otherwise the nearest section above it that
        // has one. Section assignment no longer copies the assignee onto each
        // control (the UI shows them as "inherited"), so matching the control's
        // own column alone registered NO items for a section-level owner — an
        // empty checklist that could never be completed. The auditor side had a
        // section-path fallback, but only when the person had no direct control
        // at all, and only for controls sitting directly in the section.
        boolean auditorSide = "CONTROLS_EVALUATED".equalsIgnoreCase(sectionKey);
        List<AuditControlInstance> all = controlInstanceRepository.findByEngagementId(engagement.getId());
        List<AuditControlInstance> controls;
        if (assignedUserId == null) {
            controls = all;
        } else {
            java.util.Map<Long, AuditSectionInstance> sections = new java.util.HashMap<>();
            sectionInstanceRepository.findByEngagementIdOrderByPathAscOrderNoAsc(engagement.getId())
                    .forEach(x -> sections.put(x.getId(), x));
            controls = all.stream()
                    .filter(c -> assignedUserId.equals(effectiveOwner(c, sections, auditorSide)))
                    .toList();
        }

        if (controls.isEmpty()) {
            log.warn("[AUDIT-CTRL-REGISTRAR] No control instances for engagementId={} userId={} sectionKey={} — " +
                            "either no assignments yet or snapshotTemplate() has not run",
                    engagement.getId(), assignedUserId, sectionKey);
            return;
        }

        // Register each control instance as a section item
        // itemRefType = AUDIT_CONTROL_INSTANCE, itemRefId = controlInstance.id
        // itemLabel = control name snapshot (truncated to 200 chars)
        List<TaskSectionCompletionService.ItemRegistration> registrations = controls.stream()
                .map(c -> new TaskSectionCompletionService.ItemRegistration(
                        ITEM_REF_TYPE,
                        c.getId(),
                        truncate(c.getControlNameSnapshot() != null
                                ? c.getControlNameSnapshot()
                                : c.getControlCodeSnapshot() != null
                                  ? c.getControlCodeSnapshot()
                                  : "Control " + c.getId(), 200)
                ))
                .toList();

        sectionService.registerItems(
                event.taskInstanceId(),
                event.sectionKey(),
                registrations
        );

        log.info("[AUDIT-CTRL-REGISTRAR] Registered {} control items | " +
                        "engagementId={} | sectionKey={} | taskInstanceId={}",
                registrations.size(), engagement.getId(),
                event.sectionKey(), event.taskInstanceId());
    }

    /** The control's own assignee on that side, else the nearest section above it with one. */
    public static Long effectiveOwner(AuditControlInstance c, java.util.Map<Long, AuditSectionInstance> sections, boolean auditorSide) {
        Long own = auditorSide ? c.getAssignedAuditorId() : c.getAuditeeAssignedUserId();
        if (own != null) return own;
        Long cur = c.getSectionInstanceId();
        for (int hops = 0; cur != null && hops < 64; hops++) {
            AuditSectionInstance s = sections.get(cur);
            if (s == null) return null;
            Long owner = auditorSide ? s.getAssignedAuditorId() : s.getAuditeeAssignedUserId();
            if (owner != null) return owner;
            cur = s.getParentInstanceId();
        }
        return null;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }
}