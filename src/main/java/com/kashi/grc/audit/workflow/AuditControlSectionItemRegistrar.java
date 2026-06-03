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

        // Load only control instances assigned to this specific auditee.
        // event.assignedUserId() is the userId on the TaskInstance being registered.
        // Using findByEngagementId (all controls) would register every control on
        // every auditee's task — the auditee would see controls they never owned.
        Long assignedAuditeeId = event.assignedUserId();
        List<AuditControlInstance> controls = (assignedAuditeeId != null)
                ? controlInstanceRepository.findByEngagementIdAndAuditeeAssignedUserId(
                engagement.getId(), assignedAuditeeId)
                : controlInstanceRepository.findByEngagementId(engagement.getId()); // fallback: no auditee filter

        if (controls.isEmpty()) {
            log.warn("[AUDIT-CTRL-REGISTRAR] No control instances for engagementId={} auditeeId={} — " +
                    "either no assignments yet or snapshotTemplate() has not run", engagement.getId(), assignedAuditeeId);
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