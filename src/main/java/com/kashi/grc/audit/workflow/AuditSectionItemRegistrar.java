package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditProjectInstanceRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.workflow.event.SectionItemsNeededEvent;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * AuditSectionItemRegistrar — registers AuditSectionInstance rows as TaskSectionItems
 * when a workflow step has tracksItems=true and itemRefType="AUDIT_SECTION_INSTANCE".
 *
 * Used for Steps 2 (assign sections to auditors) and Step 3 (assign sections to auditees).
 *
 * For WF16 (Audit Project Lifecycle), the workflow instance belongs to the PROJECT,
 * not to individual engagements. This registrar resolves the project from the
 * workflow instance ID and registers sections from ALL engagements under it, so
 * the section gate only closes when every section across every engagement is assigned.
 *
 * For WF14 (individual engagement lifecycle), the workflow instance belongs to a
 * single engagement — the original path still works unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditSectionItemRegistrar {

    private static final String ITEM_REF_TYPE        = "AUDIT_SECTION_INSTANCE";
    private static final int    DEFAULT_ASSIGN_DEPTH = 0; // root categories only

    private final AuditEngagementRepository      engagementRepository;
    private final AuditProjectInstanceRepository projectInstanceRepository;
    private final AuditSectionInstanceRepository sectionInstanceRepository;
    private final TaskSectionCompletionService   sectionService;

    @EventListener
    @Transactional
    public void onSectionItemsNeeded(SectionItemsNeededEvent event) {
        if (!ITEM_REF_TYPE.equalsIgnoreCase(event.itemRefType())) return;

        log.info("[AUDIT-SEC-REGISTRAR] Registering section items | " +
                        "workflowInstanceId={} | sectionKey={} | taskInstanceId={}",
                event.workflowInstanceId(), event.sectionKey(), event.taskInstanceId());

        int assignDepth = resolveAssignDepth(event.sectionUiJson());

        // ── Resolve engagements ────────────────────────────────────────────────
        // Try engagement-own workflow first (WF14: engagement has its own wf instance).
        // Fall back to project-level workflow (WF16: wf instance belongs to project).
        List<AuditEngagement> engagements = new ArrayList<>();

        var directEngagement = engagementRepository
                .findByTenantIdAndWorkflowInstanceId(event.tenantId(), event.workflowInstanceId())
                .orElse(null);

        if (directEngagement != null) {
            // WF14 path — single engagement
            engagements.add(directEngagement);
            log.debug("[AUDIT-SEC-REGISTRAR] WF14 path — single engagementId={}", directEngagement.getId());
        } else {
            // WF16 path — project-level workflow
            var projectInstance = projectInstanceRepository
                    .findByWorkflowInstanceId(event.workflowInstanceId())
                    .orElse(null);
            if (projectInstance == null) {
                log.warn("[AUDIT-SEC-REGISTRAR] No engagement or project found for " +
                                "workflowInstanceId={} tenantId={} — skipping",
                        event.workflowInstanceId(), event.tenantId());
                return;
            }
            engagements = engagementRepository.findByProjectInstanceId(projectInstance.getId());
            log.info("[AUDIT-SEC-REGISTRAR] WF16 path — projectInstanceId={} has {} engagement(s)",
                    projectInstance.getId(), engagements.size());
        }

        if (engagements.isEmpty()) {
            log.warn("[AUDIT-SEC-REGISTRAR] No engagements found — skipping item registration");
            return;
        }

        // ── Register section items from ALL engagements ────────────────────────
        List<TaskSectionCompletionService.ItemRegistration> registrations = new ArrayList<>();

        for (AuditEngagement engagement : engagements) {
            List<AuditSectionInstance> sections;
            if (assignDepth < 0) {
                sections = sectionInstanceRepository
                        .findByEngagementIdOrderByPathAscOrderNoAsc(engagement.getId());
            } else {
                sections = sectionInstanceRepository
                        .findByEngagementIdAndDepthOrderByPathAscOrderNoAsc(engagement.getId(), assignDepth);
            }

            if (sections.isEmpty()) {
                log.warn("[AUDIT-SEC-REGISTRAR] No section instances at depth={} for engagementId={}" +
                        " — snapshotTemplate() may not have run yet", assignDepth, engagement.getId());
                continue;
            }

            for (AuditSectionInstance s : sections) {
                registrations.add(new TaskSectionCompletionService.ItemRegistration(
                        ITEM_REF_TYPE,
                        s.getId(),
                        truncate(buildLabel(s), 200)
                ));
            }

            log.debug("[AUDIT-SEC-REGISTRAR] Queued {} section items from engagementId={}",
                    sections.size(), engagement.getId());
        }

        if (registrations.isEmpty()) {
            log.warn("[AUDIT-SEC-REGISTRAR] No section items to register — skipping");
            return;
        }

        sectionService.registerItems(
                event.taskInstanceId(),
                event.sectionKey(),
                registrations
        );

        log.info("[AUDIT-SEC-REGISTRAR] Registered {} section items across {} engagement(s) at depth={} | " +
                        "sectionKey={} | taskInstanceId={}",
                registrations.size(), engagements.size(), assignDepth,
                event.sectionKey(), event.taskInstanceId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int resolveAssignDepth(String sectionUiJson) {
        if (sectionUiJson == null || sectionUiJson.isBlank()) return DEFAULT_ASSIGN_DEPTH;
        try {
            int idx = sectionUiJson.indexOf("\"assignDepth\"");
            if (idx < 0) return DEFAULT_ASSIGN_DEPTH;
            String after = sectionUiJson.substring(idx + "\"assignDepth\"".length()).stripLeading();
            if (after.startsWith(":")) {
                String numStr = after.substring(1).stripLeading().replaceAll("[^\\-0-9].*", "");
                if (!numStr.isEmpty()) return Integer.parseInt(numStr);
            }
        } catch (Exception e) {
            log.warn("[AUDIT-SEC-REGISTRAR] Could not parse assignDepth: {}", e.getMessage());
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