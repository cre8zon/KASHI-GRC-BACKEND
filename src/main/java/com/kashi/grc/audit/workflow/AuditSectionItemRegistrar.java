package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.workflow.event.SectionItemsNeededEvent;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AuditSectionItemRegistrar — registers AuditSectionInstance rows as TaskSectionItems
 * when a workflow step has tracksItems=true and itemRefType="AUDIT_SECTION_INSTANCE".
 *
 * Used for Steps 2 (assign sections → auditors) and Step 3 (assign sections → auditees).
 * Mirrors AuditControlSectionItemRegistrar exactly, but registers section nodes instead
 * of individual control instances.
 *
 * ── ITEM GRANULARITY ─────────────────────────────────────────────────────────
 * The blueprint decides which depth to register by configuring the section's
 * sectionUiJson.assignDepth:
 *   0 = root categories only (CC, A, PI, C, P)   — 5 items
 *   1 = top-level sub-groups (CC1, CC2 … A1 …)   — ~15 items
 *   2 = leaf criteria level (CC6.1, CC6.2 …)      — 40+ items
 *   null / absent = ALL depths (every node registered regardless of depth)
 *
 * The registrar reads assignDepth from the event's sectionUiJson snapshot.
 * If absent, it defaults to registering depth=0 sections only (least noise).
 *
 * ── ASSIGNMENT CASCADE ────────────────────────────────────────────────────────
 * When an auditor/auditee is assigned to a section node, AuditEngagementService
 * cascades to all descendant section nodes and all controls under those nodes.
 * The registrar does NOT need to know about cascade — that is the service's job.
 * The workflow section item is marked complete when the top-level node is assigned.
 *
 * ── HOW IT CONNECTS ──────────────────────────────────────────────────────────
 * 1. Step 2 (Assign Sections to Auditors) activates.
 * 2. WorkflowEngineService.snapshotSectionsForTask() creates TaskSectionCompletion
 *    with sectionKey=SECTIONS_ASSIGNED_AUDITOR, tracksItems=true,
 *    itemRefType=AUDIT_SECTION_INSTANCE.
 * 3. snapshotSectionsForTask() fires SectionItemsNeededEvent.
 * 4. THIS LISTENER catches it, loads root AuditSectionInstance rows for the
 *    engagement (depth=0 by default), and calls sectionService.registerItems().
 * 5. CompoundSectionRenderer shows one item row per section — each marked done
 *    when the lead auditor assigns that section and calls the assignment endpoint.
 *
 * ── BACKWARD COMPATIBILITY ────────────────────────────────────────────────────
 * Only fires when:
 *   1. tracksItems = true
 *   2. itemRefType = "AUDIT_SECTION_INSTANCE"
 * All other registrars (AUDIT_CONTROL_INSTANCE, QUESTION_RESPONSE) are unaffected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditSectionItemRegistrar {

    private static final String ITEM_REF_TYPE       = "AUDIT_SECTION_INSTANCE";
    private static final int    DEFAULT_ASSIGN_DEPTH = 0; // root categories only

    private final AuditEngagementRepository      engagementRepository;
    private final AuditSectionInstanceRepository sectionInstanceRepository;
    private final TaskSectionCompletionService   sectionService;

    @EventListener
    @Transactional
    public void onSectionItemsNeeded(SectionItemsNeededEvent event) {
        if (!ITEM_REF_TYPE.equalsIgnoreCase(event.itemRefType())) return;

        log.info("[AUDIT-SEC-REGISTRAR] Registering section items | " +
                        "workflowInstanceId={} | sectionKey={} | taskInstanceId={}",
                event.workflowInstanceId(), event.sectionKey(), event.taskInstanceId());

        // Resolve engagement from workflow instance ID
        var engagement = engagementRepository
                .findByTenantIdAndWorkflowInstanceId(event.tenantId(), event.workflowInstanceId())
                .orElse(null);

        if (engagement == null) {
            log.warn("[AUDIT-SEC-REGISTRAR] No engagement for workflowInstanceId={} tenantId={} " +
                            "— startWorkflowIfConfigured() must store workflowInstanceId on engagement",
                    event.workflowInstanceId(), event.tenantId());
            return;
        }

        // Resolve assignDepth from the sectionUiJson snapshot on the event.
        // Falls back to DEFAULT_ASSIGN_DEPTH (0 = root categories) when absent.
        int assignDepth = resolveAssignDepth(event.sectionUiJson());

        // Load section instances at the configured depth.
        // Registering only at depth=0 keeps the assignment UI clean — 5 rows for SOC 2
        // (CC, A, PI, C, P) rather than 40+ leaf nodes. Cascade handles the rest.
        List<AuditSectionInstance> sections;
        if (assignDepth < 0) {
            // assignDepth = -1 → register ALL sections regardless of depth
            sections = sectionInstanceRepository
                    .findByEngagementIdOrderByPathAscOrderNoAsc(engagement.getId());
        } else {
            sections = sectionInstanceRepository
                    .findByEngagementIdAndDepthOrderByPathAscOrderNoAsc(engagement.getId(), assignDepth);
        }

        if (sections.isEmpty()) {
            log.warn("[AUDIT-SEC-REGISTRAR] No section instances at depth={} for engagementId={} " +
                    "— snapshotTemplate() may not have run yet", assignDepth, engagement.getId());
            return;
        }

        List<TaskSectionCompletionService.ItemRegistration> registrations = sections.stream()
                .map(s -> new TaskSectionCompletionService.ItemRegistration(
                        ITEM_REF_TYPE,
                        s.getId(),
                        truncate(buildLabel(s), 200)
                ))
                .toList();

        sectionService.registerItems(
                event.taskInstanceId(),
                event.sectionKey(),
                registrations
        );

        log.info("[AUDIT-SEC-REGISTRAR] Registered {} section items at depth={} | " +
                        "engagementId={} | sectionKey={} | taskInstanceId={}",
                registrations.size(), assignDepth, engagement.getId(),
                event.sectionKey(), event.taskInstanceId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads assignDepth from the sectionUiJson snapshot.
     * Expected JSON: {"assignDepth": 0}
     * Returns DEFAULT_ASSIGN_DEPTH if absent or unparseable.
     * Returns -1 if explicitly set to -1 (register all depths).
     */
    private int resolveAssignDepth(String sectionUiJson) {
        if (sectionUiJson == null || sectionUiJson.isBlank()) return DEFAULT_ASSIGN_DEPTH;
        try {
            // Simple key scan — avoids a Jackson dependency just for one int field.
            // Works for well-formed JSON: {"assignDepth": 0} or {"assignDepth":-1}
            int idx = sectionUiJson.indexOf("\"assignDepth\"");
            if (idx < 0) return DEFAULT_ASSIGN_DEPTH;
            String after = sectionUiJson.substring(idx + "\"assignDepth\"".length()).stripLeading();
            if (after.startsWith(":")) {
                String numStr = after.substring(1).stripLeading().replaceAll("[^\\-0-9].*", "");
                if (!numStr.isEmpty()) return Integer.parseInt(numStr);
            }
        } catch (Exception e) {
            log.warn("[AUDIT-SEC-REGISTRAR] Could not parse assignDepth from sectionUiJson: {}", e.getMessage());
        }
        return DEFAULT_ASSIGN_DEPTH;
    }

    private String buildLabel(AuditSectionInstance s) {
        if (s.getSectionCodeSnapshot() != null && s.getSectionNameSnapshot() != null)
            return s.getSectionCodeSnapshot() + " — " + s.getSectionNameSnapshot();
        if (s.getSectionNameSnapshot() != null) return s.getSectionNameSnapshot();
        if (s.getSectionCodeSnapshot() != null) return s.getSectionCodeSnapshot();
        return "Section " + s.getId();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }
}