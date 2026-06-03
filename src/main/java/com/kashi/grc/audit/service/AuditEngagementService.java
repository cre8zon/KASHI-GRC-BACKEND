package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.dto.request.*;
import com.kashi.grc.audit.dto.response.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.event.TaskSectionEvent;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEngagementService {

    private final AuditProjectRepository                    projectRepository;
    private final AuditProjectInstanceRepository            projectInstanceRepository;
    private final AuditEngagementRepository                 engagementRepository;
    private final AuditEngagementTemplateInstanceRepository templateInstanceRepository;
    private final AuditSectionInstanceRepository            sectionInstanceRepository;
    private final AuditControlInstanceRepository            controlInstanceRepository;
    private final AuditTemplateRepository                   templateRepository;
    private final AuditTemplateSectionMappingRepository     templateSectionMappingRepository;
    private final AuditSectionRepository                    sectionRepository;
    private final AuditSectionService                       sectionService;
    private final WorkflowEngineService                     workflowEngineService;
    private final WorkflowRepository                        workflowRepository;
    private final NotificationService                       notificationService;
    private final AuditTestPolicySnapshotService            testPolicySnapshotService;
    private final WorkflowInstanceRepository                workflowInstanceRepository;
    private final StepInstanceRepository                    stepInstanceRepository;
    private final TaskInstanceRepository                    taskInstanceRepository;
    // FIX: use ApplicationEventPublisher + TaskSectionEvent instead of calling
    // sectionCompletionService.onSectionEvent() directly — the service method
    // takes a TaskSectionEvent record, not separate parameters.
    private final ApplicationEventPublisher                 eventPublisher;

    // ── CREATE ────────────────────────────────────────────────────────────────

    @Transactional
    public AuditEngagementResponse create(AuditEngagementRequest req, Long createdBy, Long tenantId) {
        // Project is optional — standalone engagement without project is supported
        if (req.getProjectId() != null) {
            projectRepository.findByTenantIdAndId(tenantId, req.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("AuditProject", req.getProjectId()));
        }

        String ref = buildEngagementRef(tenantId);

        AuditEngagement engagement = AuditEngagement.builder()
                .engagementRef(ref)
                .projectId(req.getProjectId())
                .tenantId(tenantId)
                .name(req.getName())
                .description(req.getDescription())
                .templateId(req.getTemplateId())
                .frameworkRef(req.getFrameworkRef())
                .auditType(req.getAuditType() != null ? req.getAuditType() : AuditTemplate.AuditType.INTERNAL)
                .status(AuditEngagement.Status.PLANNING)
                .leadAuditorId(req.getLeadAuditorId())
                .ownerId(req.getOwnerId() != null ? req.getOwnerId() : createdBy)
                .createdBy(createdBy)
                // FIX: request has LocalDate, domain has LocalDateTime — convert with atStartOfDay()
                .plannedStart(req.getPlannedStart() != null ? req.getPlannedStart().atStartOfDay() : null)
                .plannedEnd(req.getPlannedEnd()     != null ? req.getPlannedEnd().atStartOfDay()   : null)
                .build();

        engagementRepository.save(engagement);
        // Deduplication guard — reject rapid double-clicks (same name+template within 60 seconds)
        boolean duplicate = engagementRepository
                .existsByTenantIdAndNameAndTemplateIdAndCreatedAtAfter(
                        tenantId, req.getName(), req.getTemplateId(),
                        LocalDateTime.now().minusSeconds(60));
        if (duplicate) {
            throw new BusinessException("DUPLICATE_ENGAGEMENT",
                    "This engagement was just created — please wait a moment before trying again");
        }

        log.info("[AUDIT] Created | ref={} | type={} | tenantId={}", ref, engagement.getAuditType(), tenantId);

        // Snapshot project — create once per project, reuse for subsequent engagements
        if (req.getProjectId() != null) {
            AuditProjectInstance projInst = projectInstanceRepository
                    .findByOriginalProjectId(req.getProjectId())
                    .orElseGet(() -> {
                        AuditProject project = projectRepository.findById(req.getProjectId())
                                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", req.getProjectId()));
                        AuditProjectInstance inst = AuditProjectInstance.builder()
                                .originalProjectId(project.getId())
                                .tenantId(tenantId)
                                .projectNameSnapshot(project.getName())
                                .projectRefSnapshot(project.getProjectRef())
                                .descriptionSnapshot(project.getDescription())
                                .ownerIdSnapshot(project.getOwnerId())
                                .plannedStartSnapshot(project.getPlannedStart())
                                .plannedEndSnapshot(project.getPlannedEnd())
                                .statusAtSnapshot(project.getStatus() != null ? project.getStatus().name() : "ACTIVE")
                                .snapshottedAt(LocalDateTime.now())
                                .snapshottedBy(createdBy)
                                .build();
                        return projectInstanceRepository.save(inst);
                    });
            engagement.setProjectInstanceId(projInst.getId());
            engagementRepository.save(engagement);
            log.info("[AUDIT] Project snapshotted | projectInstanceId={}", projInst.getId());
        }

        if (req.getTemplateId() != null) {
            snapshotTemplate(engagement, req.getTemplateId(), tenantId);
        }

        startWorkflowIfConfigured(engagement, req.getWorkflowId(), createdBy, tenantId);

        if (req.getLeadAuditorId() != null) {
            notificationService.send(req.getLeadAuditorId(), "AUDIT_ENGAGEMENT_ASSIGNED",
                    "Audit engagement " + ref + " has been assigned to you",
                    "AUDIT_ENGAGEMENT", engagement.getId());
        }

        return toResponse(engagement);
    }

    // ── TEMPLATE SNAPSHOT (recursive) ────────────────────────────────────────

    @Transactional
    public void snapshotTemplate(AuditEngagement engagement, Long templateId, Long tenantId) {
        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        AuditEngagementTemplateInstance tmplInstance = templateInstanceRepository.save(
                AuditEngagementTemplateInstance.builder()
                        .engagementId(engagement.getId())
                        .tenantId(tenantId)
                        .originalTemplateId(templateId)
                        .templateNameSnapshot(template.getName())
                        .templateVersionSnapshot(template.getVersion())
                        .frameworkRefSnapshot(template.getFrameworkRef())
                        .snapshottedAt(LocalDateTime.now())
                        .build()
        );

        List<AuditTemplateSectionMapping> rootMappings =
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNoAsc(templateId);

        for (AuditTemplateSectionMapping mapping : rootMappings) {
            AuditSection rootSection = sectionRepository.findById(mapping.getSectionId()).orElse(null);
            if (rootSection == null) continue;

            sectionService.snapshotSectionNode(
                    rootSection, null, null,
                    engagement.getId(), tmplInstance.getId(), tenantId
            );
        }

        int totalControls = (int) controlInstanceRepository.countByEngagementId(engagement.getId());
        engagement.setTotalControls(totalControls);
        engagementRepository.save(engagement);

        // Snapshot tests and policies — full isolation from library changes
        Long createdBy = engagement.getCreatedBy() != null ? engagement.getCreatedBy() : tenantId;
        testPolicySnapshotService.snapshotTestsAndPolicies(
                engagement.getId(), tenantId, createdBy);

        log.info("[AUDIT] Template snapshotted | engagementId={} | rootSections={} | totalControls={}",
                engagement.getId(), rootMappings.size(), totalControls);
    }

    // ── SECTION ASSIGNMENT ────────────────────────────────────────────────────

    @Transactional
    public void assignSection(Long engagementId, Long sectionInstanceId,
                              Long auditorId, boolean cascadeToChildren, Long tenantId) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));

        if (!section.getEngagementId().equals(engagementId))
            throw new BusinessException("SECTION_MISMATCH", "Section does not belong to this engagement");

        section.setAssignedAuditorId(auditorId);
        sectionInstanceRepository.save(section);

        if (cascadeToChildren) {
            List<AuditSectionInstance> descendants =
                    sectionInstanceRepository.findAllDescendants(sectionInstanceId, section.getPath());
            for (AuditSectionInstance child : descendants) {
                child.setAssignedAuditorId(auditorId);
                sectionInstanceRepository.save(child);
            }
            List<AuditControlInstance> controls =
                    controlInstanceRepository.findByEngagementIdAndSectionPathStartingWith(
                            engagementId, section.getPath());
            for (AuditControlInstance ctrl : controls) {
                ctrl.setAssignedAuditorId(auditorId);
                controlInstanceRepository.save(ctrl);
            }
        }

        if (auditorId != null) {
            notificationService.send(auditorId, "AUDIT_SECTION_ASSIGNED",
                    "Audit section '" + section.getSectionNameSnapshot() + "' assigned to you",
                    "AUDIT_SECTION_INSTANCE", sectionInstanceId);
        }

        // Advance compound section gate in Step 2 (SECTIONS_ASSIGNED_AUDITOR)
        fireSectionAssignmentEvent("SECTIONS_ASSIGNED_AUDITOR",
                sectionInstanceId, engagementId, auditorId != null ? auditorId : 0L);

        log.info("[AUDIT] Section assigned | sectionInstanceId={} | auditorId={} | cascade={}",
                sectionInstanceId, auditorId, cascadeToChildren);
    }

    // ── AUDITEE SECTION ASSIGNMENT ────────────────────────────────────────────

    /**
     * Assigns an auditee user as the evidence owner for a section and its entire subtree.
     *
     * Cascades to all descendant section nodes (all depths) and to all control instances
     * whose sectionPath falls within this section's path prefix — exactly mirroring
     * the auditor cascade in assignSection().
     *
     * Fired by Step 3 (Assign Evidence Owners) in the SOC 2 workflow blueprint.
     * The compound section gate SECTIONS_ASSIGNED_AUDITEE is advanced when the lead
     * auditor calls this endpoint for each section item registered by AuditSectionItemRegistrar.
     *
     * @param engagementId      Parent engagement — validates section ownership
     * @param sectionInstanceId The section node being assigned (typically depth=0)
     * @param auditeeUserId     The auditee user who will upload evidence. Null = un-assign.
     * @param cascadeToChildren When true (default), all child sections and controls inherit
     *                          this auditee assignment. Set false only for leaf-level overrides.
     * @param tenantId          Caller's tenant — used for security scoping
     */
    @Transactional
    public void assignAuditeeToSection(Long engagementId, Long sectionInstanceId,
                                       Long auditeeUserId, boolean cascadeToChildren,
                                       Long tenantId) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));

        if (!section.getEngagementId().equals(engagementId))
            throw new BusinessException("SECTION_MISMATCH", "Section does not belong to this engagement");

        section.setAuditeeAssignedUserId(auditeeUserId);
        sectionInstanceRepository.save(section);

        if (cascadeToChildren) {
            // Cascade to all descendant section nodes
            List<AuditSectionInstance> descendants =
                    sectionInstanceRepository.findAllDescendants(sectionInstanceId, section.getPath());
            for (AuditSectionInstance child : descendants) {
                child.setAuditeeAssignedUserId(auditeeUserId);
                sectionInstanceRepository.save(child);
            }
            // Cascade to all controls under this section subtree
            List<AuditControlInstance> controls =
                    controlInstanceRepository.findByEngagementIdAndSectionPathStartingWith(
                            engagementId, section.getPath());
            for (AuditControlInstance ctrl : controls) {
                ctrl.setAuditeeAssignedUserId(auditeeUserId);
                controlInstanceRepository.save(ctrl);
            }
        }

        if (auditeeUserId != null) {
            notificationService.send(auditeeUserId, "AUDIT_SECTION_AUDITEE_ASSIGNED",
                    "Audit section '" + section.getSectionNameSnapshot() + "' assigned to you for evidence",
                    "AUDIT_SECTION_INSTANCE", sectionInstanceId);
        }

        // Advance compound section gate in Step 3 (SECTIONS_ASSIGNED_AUDITEE)
        fireSectionAssignmentEvent("SECTIONS_ASSIGNED_AUDITEE",
                sectionInstanceId, engagementId, auditeeUserId != null ? auditeeUserId : 0L);

        log.info("[AUDIT] Auditee assigned to section | sectionInstanceId={} | auditeeUserId={} | cascade={}",
                sectionInstanceId, auditeeUserId, cascadeToChildren);
    }

    // ── AUDITEE CONTROL ASSIGNMENT ────────────────────────────────────────────

    @Transactional
    public void assignAuditeeToControl(Long engagementId, Long controlInstanceId,
                                       Object auditeeUserIdRaw,
                                       java.time.LocalDate evidenceDueDate, Long tenantId) {
        Long auditeeUserId = auditeeUserIdRaw != null
                ? Long.parseLong(auditeeUserIdRaw.toString()) : null;
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", controlInstanceId));
        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH", "Control does not belong to this engagement");

        control.setAuditeeAssignedUserId(auditeeUserId);
        if (evidenceDueDate != null) control.setEvidenceDueDate(evidenceDueDate);
        controlInstanceRepository.save(control);

        if (auditeeUserId != null) {
            String dueDateStr = evidenceDueDate != null ? " (due " + evidenceDueDate + ")" : "";
            notificationService.send(auditeeUserId, "AUDIT_EVIDENCE_REQUESTED",
                    "Evidence requested for audit control: " + control.getControlNameSnapshot() + dueDateStr,
                    "AUDIT_CONTROL_INSTANCE", controlInstanceId);
        }
    }

    /** Backward-compat overload — no due date */
    public void assignAuditeeToControl(Long engagementId, Long controlInstanceId,
                                       Object auditeeUserIdRaw, Long tenantId) {
        assignAuditeeToControl(engagementId, controlInstanceId, auditeeUserIdRaw, null, tenantId);
    }

    public void assignAuditorToControl(Long engagementId, Long controlInstanceId,
                                       Long auditorId, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance",
                        controlInstanceId));

        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH",
                    "Control does not belong to this engagement");

        control.setAssignedAuditorId(auditorId);
        controlInstanceRepository.save(control);

        // Fire section completion event for compound section gate
        // (Step 2 blueprint section CONTROLS_ASSIGNED tracks these)
        fireControlSectionEvent("CONTROLS_ASSIGNED", controlInstanceId, engagementId, auditorId);

        log.info("[AUDIT-ENG-SERVICE] Auditor assigned | controlInstanceId={} | auditorId={}",
                controlInstanceId, auditorId);
    }

    public void submitControlEvidence(Long engagementId, Long controlInstanceId,
                                      Long submittedBy, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance",
                        controlInstanceId));

        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH",
                    "Control does not belong to this engagement");

        control.setAuditeeEvidenceSubmitted(true);
        control.setAuditeeEvidenceSubmittedAt(LocalDateTime.now());
        controlInstanceRepository.save(control);

        // Fire section completion — advances compound section gate in workflow step
        fireControlSectionEvent("EVIDENCE_UPLOADED", controlInstanceId, engagementId, submittedBy);

        log.info("[AUDIT-ENG-SERVICE] Evidence submitted | controlInstanceId={} | by={}",
                controlInstanceId, submittedBy);
    }

    // ── CONTROL TEST RESULT ───────────────────────────────────────────────────

    @Transactional
    public AuditControlInstance recordTestResult(Long engagementId, Long controlInstanceId,
                                                 AuditControlTestRequest req,
                                                 Long testedBy, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", controlInstanceId));

        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH", "Control does not belong to this engagement");

        control.setTestResult(req.getTestResult());
        control.setTestNotes(req.getTestNotes());
        control.setTestProcedure(req.getTestProcedure());
        control.setTestedAt(LocalDateTime.now());
        control.setTestedBy(testedBy);

        if (req.getFindingIssueId() != null) {
            control.setFindingLinked(true);
            control.setFindingIssueId(req.getFindingIssueId());
        }

        controlInstanceRepository.save(control);
        updateEngagementCounts(engagementId);

        // Fire section completion event so compound section gate advances the workflow
        // step when all of the auditor's controls have been tested.
        fireControlSectionEvent("TEST_RECORDED", controlInstanceId, engagementId, testedBy);

        log.info("[AUDIT] Control tested | controlInstanceId={} | result={}",
                controlInstanceId, req.getTestResult());
        return control;
    }

    // ── SUBMISSION ────────────────────────────────────────────────────────────

    @Transactional
    public void submitSection(Long engagementId, Long sectionInstanceId,
                              boolean cascadeToChildren, Long submittedBy, Long tenantId) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));

        if (section.getSubmittedAt() != null)
            throw new BusinessException("SECTION_ALREADY_SUBMITTED", "Section already submitted");

        LocalDateTime now = LocalDateTime.now();
        section.setSubmittedAt(now);
        section.setSubmittedBy(submittedBy);
        sectionInstanceRepository.save(section);

        if (cascadeToChildren) {
            List<AuditSectionInstance> descendants =
                    sectionInstanceRepository.findAllDescendants(sectionInstanceId, section.getPath());
            for (AuditSectionInstance child : descendants) {
                if (child.getSubmittedAt() == null) {
                    child.setSubmittedAt(now);
                    child.setSubmittedBy(submittedBy);
                    sectionInstanceRepository.save(child);
                }
            }
        }
    }

    @Transactional
    public void reopenSection(Long engagementId, Long sectionInstanceId, Long reopenedBy) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));
        section.setSubmittedAt(null);
        section.setSubmittedBy(null);
        section.setReopenedAt(LocalDateTime.now());
        section.setReopenedBy(reopenedBy);
        sectionInstanceRepository.save(section);
    }

    // ── STATS ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getEngagementStats(Long engagementId, Long tenantId) {
        AuditEngagement e = engagementRepository.findById(engagementId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", engagementId));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalControls",      e.getTotalControls());
        stats.put("testedControls",     e.getTestedControls());
        stats.put("passedControls",     e.getPassedControls());
        stats.put("failedControls",     e.getFailedControls());
        stats.put("openFindings",       e.getOpenFindingCount());

        long totalSections     = sectionInstanceRepository.countTotalByEngagement(engagementId);
        long submittedSections = sectionInstanceRepository.countSubmittedByEngagement(engagementId);
        stats.put("totalSections",     totalSections);
        stats.put("submittedSections", submittedSections);

        Map<String, Long> resultBreakdown = new LinkedHashMap<>();
        controlInstanceRepository.countByResultForEngagement(engagementId)
                .forEach(r -> resultBreakdown.put(
                        r[0] != null ? r[0].toString() : "NOT_TESTED", (Long) r[1]));
        stats.put("resultBreakdown", resultBreakdown);

        double progress = e.getTotalControls() > 0
                ? (double) e.getTestedControls() / e.getTotalControls() * 100 : 0.0;
        stats.put("progressPct", Math.round(progress));

        return stats;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the active TaskInstance for the acting user on this engagement's
     * current workflow step, then fires a section completion event.
     *
     * Called after domain actions that drive workflow step advancement:
     *   - recordTestResult()      → fires "TEST_RECORDED"
     *   - submitControlEvidence() → fires "EVIDENCE_UPLOADED"
     *   - assignAuditorToControl() → fires "CONTROLS_ASSIGNED"
     *
     * If no active task is found (e.g. no workflow configured, or the step
     * doesn't track this event), the call is a safe no-op — logged at DEBUG.
     *
     * FIX vs uploaded version: fireControlSectionEvent is a class-level private method,
     * NOT nested inside recordTestResult. Java does not allow method declarations
     * inside method bodies. The event is published via ApplicationEventPublisher
     * using TaskSectionEvent.sectionDone() — the correct call signature.
     *
     * @param completionEvent  Must match sectionKey completionEvent in blueprint step section config
     *                         e.g. "TEST_RECORDED", "EVIDENCE_UPLOADED", "CONTROLS_ASSIGNED"
     * @param controlInstanceId The control that was just acted upon
     * @param engagementId      The parent engagement
     * @param userId            The user performing the action (auditor or auditee)
     */

    /**
     * Fires a TaskSectionEvent to advance the compound section gate when a section
     * node is assigned (auditor or auditee). Mirrors fireControlSectionEvent but
     * uses AUDIT_SECTION_INSTANCE as the itemRefType.
     *
     * Called by:
     *   assignSection()          → fires "SECTIONS_ASSIGNED_AUDITOR"  (Step 2 gate)
     *   assignAuditeeToSection() → fires "SECTIONS_ASSIGNED_AUDITEE"  (Step 3 gate)
     *
     * The actorUserId is the ACTOR who performed the assignment (lead auditor),
     * not the assignee — the lead auditor's task is the one that needs to advance.
     */
    private void fireSectionAssignmentEvent(String completionEvent,
                                            Long sectionInstanceId,
                                            Long engagementId,
                                            Long actorUserId) {
        if (actorUserId == null || actorUserId == 0L) return;
        try {
            AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
            if (engagement == null || engagement.getWorkflowInstanceId() == null) {
                log.debug("[AUDIT-ENG-SERVICE] No workflow instance for engagementId={} — " +
                        "skipping section assignment event '{}'", engagementId, completionEvent);
                return;
            }
            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(
                            engagement.getWorkflowInstanceId(), StepStatus.IN_PROGRESS);
            if (activeSteps.isEmpty()) return;
            var stepInstance = activeSteps.get(0);
            var actorTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                    .stream()
                    .filter(t -> actorUserId.equals(t.getAssignedUserId())
                            && t.getTaskRole() == TaskRole.ACTOR)
                    .findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                            .stream()
                            .filter(t -> actorUserId.equals(t.getAssignedUserId())
                                    && t.getTaskRole() == TaskRole.ACTOR)
                            .findFirst());
            if (actorTask.isEmpty()) {
                log.debug("[AUDIT-ENG-SERVICE] No ACTOR task for userId={} at stepInstanceId={} — " +
                        "skipping section event '{}'", actorUserId, stepInstance.getId(), completionEvent);
                return;
            }
            eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                    completionEvent,
                    actorTask.get().getId(),
                    actorUserId,
                    "AUDIT_SECTION_INSTANCE",
                    sectionInstanceId
            ));
            log.info("[AUDIT-ENG-SERVICE] Section assignment event fired | event='{}' | " +
                            "sectionInstanceId={} | taskInstanceId={} | actorUserId={}",
                    completionEvent, sectionInstanceId, actorTask.get().getId(), actorUserId);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Section assignment event '{}' failed (non-fatal) | " +
                    "sectionInstanceId={} | {}", completionEvent, sectionInstanceId, ex.getMessage());
        }
    }

    private void fireControlSectionEvent(String completionEvent,
                                         Long controlInstanceId,
                                         Long engagementId,
                                         Long userId) {
        try {
            // Find the engagement's active workflow instance
            AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
            if (engagement == null || engagement.getWorkflowInstanceId() == null) {
                log.debug("[AUDIT-ENG-SERVICE] No workflow instance for engagementId={} — " +
                        "skipping section event '{}'", engagementId, completionEvent);
                return;
            }

            // Find the currently IN_PROGRESS step instance
            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(
                            engagement.getWorkflowInstanceId(), StepStatus.IN_PROGRESS);

            if (activeSteps.isEmpty()) {
                log.debug("[AUDIT-ENG-SERVICE] No IN_PROGRESS step for workflowInstanceId={} — " +
                                "skipping section event '{}'",
                        engagement.getWorkflowInstanceId(), completionEvent);
                return;
            }

            var stepInstance = activeSteps.get(0);

            // Find the task for this user at this step (ACTOR task only)
            // Try IN_PROGRESS first, fall back to PENDING (task may not have been opened yet)
            var actorTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                    .stream()
                    .filter(t -> userId.equals(t.getAssignedUserId())
                            && t.getTaskRole() == TaskRole.ACTOR)
                    .findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                            .stream()
                            .filter(t -> userId.equals(t.getAssignedUserId())
                                    && t.getTaskRole() == TaskRole.ACTOR)
                            .findFirst());

            if (actorTask.isEmpty()) {
                log.debug("[AUDIT-ENG-SERVICE] No ACTOR task for userId={} at stepInstanceId={} — " +
                        "skipping section event '{}'", userId, stepInstance.getId(), completionEvent);
                return;
            }

            Long taskInstanceId = actorTask.get().getId();

            eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                    completionEvent,
                    taskInstanceId,
                    userId,
                    "AUDIT_CONTROL_INSTANCE",
                    controlInstanceId
            ));

            log.info("[AUDIT-ENG-SERVICE] Section event fired | event='{}' | " +
                            "controlInstanceId={} | taskInstanceId={} | userId={}",
                    completionEvent, controlInstanceId, taskInstanceId, userId);

        } catch (Exception ex) {
            // Never let section event failure break the domain action
            log.warn("[AUDIT-ENG-SERVICE] Section event '{}' failed (non-fatal) | " +
                    "controlInstanceId={} | {}", completionEvent, controlInstanceId, ex.getMessage());
        }
    }

    private void startWorkflowIfConfigured(AuditEngagement engagement,
                                           Long overrideWorkflowId,
                                           Long initiatedBy, Long tenantId) {
        Long workflowId = overrideWorkflowId;
        if (workflowId == null) {
            String expectedName = "AUDIT_ENGAGEMENT_" + engagement.getAuditType().name();
            workflowId = workflowRepository.findAll().stream()
                    .filter(w -> w.isActive() && expectedName.equals(w.getName()))
                    .findFirst().map(w -> w.getId()).orElse(null);
        }
        if (workflowId == null) {
            log.warn("[AUDIT] No workflow configured for auditType={}", engagement.getAuditType());
            return;
        }
        try {
            StartWorkflowRequest req = new StartWorkflowRequest();
            req.setWorkflowId(workflowId);
            req.setEntityType("AUDIT_ENGAGEMENT");
            req.setEntityId(engagement.getId());
            req.setPriority("MEDIUM");
            // FIX: plannedEnd is LocalDateTime — no .atStartOfDay() needed, setDueDate takes LocalDateTime
            if (engagement.getPlannedEnd() != null)
                req.setDueDate(engagement.getPlannedEnd());

            WorkflowInstanceResponse wf = workflowEngineService.startWorkflow(req, tenantId, initiatedBy);
            engagement.setWorkflowInstanceId(wf.getId());
            engagementRepository.save(engagement);
            log.info("[AUDIT] Workflow started | engagementId={} | instanceId={}",
                    engagement.getId(), wf.getId());
        } catch (Exception e) {
            log.error("[AUDIT] Workflow start failed | engagementId={} | {}",
                    engagement.getId(), e.getMessage());
        }
    }

    private void updateEngagementCounts(Long engagementId) {
        long tested = controlInstanceRepository.countTestedByEngagement(engagementId);
        Map<String, Long> breakdown = new LinkedHashMap<>();
        controlInstanceRepository.countByResultForEngagement(engagementId)
                .forEach(r -> breakdown.put(r[0] != null ? r[0].toString() : "NOT_TESTED", (Long) r[1]));

        engagementRepository.findById(engagementId).ifPresent(e -> {
            e.setTestedControls((int) tested);
            e.setPassedControls(breakdown.getOrDefault("EFFECTIVE", 0L).intValue());
            e.setFailedControls(breakdown.getOrDefault("INEFFECTIVE", 0L).intValue());
            engagementRepository.save(e);
        });
    }

    private String buildEngagementRef(Long tenantId) {
        long seq = engagementRepository.nextEngagementRefSequence(tenantId);
        return String.format("ENG-%d-%04d", LocalDateTime.now().getYear(), seq);
    }

    private AuditEngagementResponse toResponse(AuditEngagement e) {
        return AuditEngagementResponse.builder()
                .id(e.getId()).engagementRef(e.getEngagementRef())
                .projectId(e.getProjectId()).name(e.getName()).description(e.getDescription())
                .auditType(e.getAuditType()).status(e.getStatus()).frameworkRef(e.getFrameworkRef())
                .leadAuditorId(e.getLeadAuditorId()).ownerId(e.getOwnerId())
                .totalControls(e.getTotalControls()).testedControls(e.getTestedControls())
                .passedControls(e.getPassedControls()).failedControls(e.getFailedControls())
                .openFindingCount(e.getOpenFindingCount()).workflowInstanceId(e.getWorkflowInstanceId())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                // FIX: response expects LocalDate, domain has LocalDateTime — convert with toLocalDate()
                .plannedStart(e.getPlannedStart() != null ? e.getPlannedStart().toLocalDate() : null)
                .plannedEnd(e.getPlannedEnd()     != null ? e.getPlannedEnd().toLocalDate()   : null)
                .listScreenKey(e.getListScreenKey()).detailScreenKey(e.getDetailScreenKey())
                .build();
    }
}