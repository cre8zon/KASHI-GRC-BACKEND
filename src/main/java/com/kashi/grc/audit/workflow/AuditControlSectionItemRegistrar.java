package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.workflow.event.SectionItemsNeededEvent;
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
    private final TaskSectionCompletionService   sectionService;

    @EventListener
    @Transactional
    public void onSectionItemsNeeded(SectionItemsNeededEvent event) {
        // Only handle AUDIT_CONTROL_INSTANCE sections
        if (!ITEM_REF_TYPE.equalsIgnoreCase(event.itemRefType())) return;

        log.info("[AUDIT-CTRL-REGISTRAR] Registering control items | " +
                        "workflowInstanceId={} | sectionKey={} | taskInstanceId={}",
                event.workflowInstanceId(), event.sectionKey(), event.taskInstanceId());

        // Resolve engagement from workflow instance
        var engagement = engagementRepository
                .findByTenantIdAndWorkflowInstanceId(event.tenantId(), event.workflowInstanceId())
                .orElse(null);

        if (engagement == null) {
            log.warn("[AUDIT-CTRL-REGISTRAR] No engagement found for workflowInstanceId={} tenantId={}" +
                            " — startWorkflowIfConfigured() must have stored workflowInstanceId on engagement",
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

        List<AuditControlInstance> controls;
        if ("CONTROLS_EVALUATED".equalsIgnoreCase(sectionKey)) {
            // Auditor side — find controls assigned to this auditor
            // If assignedAuditorId is not set (cascade removed), fall back to
            // controls under this auditor's assigned sections
            controls = (assignedUserId != null)
                    ? controlInstanceRepository.findByEngagementIdAndAssignedAuditorId(
                    engagement.getId(), assignedUserId)
                    : controlInstanceRepository.findByEngagementId(engagement.getId());
            if (controls.isEmpty() && assignedUserId != null) {
                // Fallback: auditor assigned to sections — get all controls under those sections
                log.info("[AUDIT-CTRL-REGISTRAR] No direct control assignments for auditorId={} — falling back to section-path lookup",
                        assignedUserId);
                controls = controlInstanceRepository
                        .findByEngagementIdAndSectionAuditorId(engagement.getId(), assignedUserId);
            }
        } else {
            // EVIDENCE_UPLOADED or any other auditee-side section key
            controls = (assignedUserId != null)
                    ? controlInstanceRepository.findByEngagementIdAndAuditeeAssignedUserId(
                    engagement.getId(), assignedUserId)
                    : controlInstanceRepository.findByEngagementId(engagement.getId());
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

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }
}