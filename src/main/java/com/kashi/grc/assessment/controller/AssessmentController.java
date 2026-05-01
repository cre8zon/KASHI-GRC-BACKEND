package com.kashi.grc.assessment.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.guard.service.GuardEvaluator;
import com.kashi.grc.guard.event.ModuleSubmitEvent;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.dto.request.*;
import com.kashi.grc.assessment.dto.response.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.dto.request.TaskActionRequest;
import com.kashi.grc.workflow.dto.response.TaskInstanceResponse;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.enums.ActionType;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import org.springframework.context.ApplicationEventPublisher;
import com.kashi.grc.workflow.repository.*;
import com.kashi.grc.assessment.repository.ContributorSectionSubmissionRepository;
import com.kashi.grc.assessment.domain.ContributorSectionSubmission;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AssessmentController — assessment responses, reviews, and workflow task actions.
 *
 * ══ STEP-GATED ACCESS CONTROL (new) ══════════════════════════════════════════
 *
 * assertUserHasActiveTask() is now called at the top of:
 *   - getAssessment()       — vendor view of the assessment form
 *   - submitAnswer()        — vendor submitting a response to a question
 *   - reviewAssessment()    — org reviewer loading the submitted assessment
 *
 * This ensures that only users who have been explicitly assigned a PENDING task
 * for the current workflow step can access the assessment data. Any authenticated
 * vendor user who knows an assessmentId but has no active task will receive 403.
 *
 * The guard works by:
 *   1. Loading the VendorAssessment → VendorAssessmentCycle → WorkflowInstance.
 *   2. Looking up all PENDING TaskInstances for the current user.
 *   3. Checking if any of those tasks belong to the active workflow instance.
 *   4. If none found → 403 ACCESS_DENIED.
 *
 * If no workflow instance is linked to the cycle (e.g. org-only assessments without
 * a workflow), the guard passes without restriction.
 *
 * ══ GETMYTASKS ENDPOINT — uses WorkflowEngineService enriched mapper ══════════
 *
 * getMyTasks() now delegates task fetching to workflowEngineService.getPendingTasksForUser()
 * and workflowEngineService.getAllTasksForUser() which return the enriched
 * TaskInstanceResponse with stepName, entityType, entityId, priority, and workflowName.
 *
 * This is the single source of truth for task enrichment — the frontend TaskInbox
 * and resolveTaskRoute() receive all the data they need from this endpoint.
 *
 * ══ ALL OTHER METHODS ARE UNCHANGED ══════════════════════════════════════════
 *   executeAssessment, getVendorAssessments, submitAnswer (guard added only),
 *   addComment, submitAssessment, reviewAssessment (guard added only),
 *   listAssessments, actOnTask, getInstanceStatus, assignSection,
 *   assignQuestion, getMySections, getMyQuestions, buildSectionInstances.
 */
@Slf4j
@RestController
@Tag(name = "Assessment & Workflow Actions", description = "Assessment responses, reviews, and workflow actions")
@RequiredArgsConstructor
public class AssessmentController {

    private final VendorAssessmentRepository           assessmentRepository;
    private final VendorAssessmentCycleRepository      cycleRepository;
    private final AssessmentTemplateInstanceRepository templateInstanceRepository;
    private final AssessmentSectionInstanceRepository  sectionInstanceRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final AssessmentOptionInstanceRepository   optionInstanceRepository;
    private final AssessmentResponseRepository         responseRepository;
    private final QuestionCommentRepository            commentRepository;
    private final AssessmentTemplateRepository         templateRepository;
    private final AssessmentSectionRepository          sectionRepository;
    private final AssessmentQuestionRepository         questionRepository;
    private final AssessmentQuestionOptionRepository   optionRepository;
    private final TemplateSectionMappingRepository     templateSectionMappingRepository;
    private final SectionQuestionMappingRepository     sectionQuestionMappingRepository;
    private final QuestionOptionMappingRepository      questionOptionMappingRepository;
    private final VendorRepository                     vendorRepository;
    private final RiskTemplateMappingRepository        mappingRepository;
    private final WorkflowInstanceRepository           workflowInstanceRepository;
    private final WorkflowStepRepository               stepRepository;
    private final StepInstanceRepository               stepInstanceRepository;
    private final TaskInstanceRepository               taskInstanceRepository;
    private final WorkflowEngineService                workflowEngineService;
    private final TaskSectionCompletionService         sectionCompletionService;   // Gap 3
    private final ApplicationEventPublisher            eventPublisher;             // Gap 3
    private final DbRepository                         dbRepository;
    private final com.kashi.grc.usermanagement.repository.UserRepository userRepository;
    private final com.kashi.grc.workflow.repository.TaskSectionItemRepository taskSectionItemRepository;
    private final ContributorSectionSubmissionRepository contributorSectionSubmissionRepository;
    private final UtilityService                       utilityService;
    private final GuardEvaluator                        guardEvaluator;
    private final ActionItemRepository                   actionItemRepository;
    private final NotificationService                    notificationService;

    // ── 9.1 Execute Assessment (logic unchanged) ───────────────────────────────

    @PostMapping("/v1/workflows/tasks/{taskId}/execute-assessment")
    @Transactional
    @Operation(summary = "System: snapshot template into instances, create assessment, advance workflow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> executeAssessment(
            @PathVariable Long taskId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        TaskInstance task = taskInstanceRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));
        StepInstance stepInst = stepInstanceRepository.findById(task.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", task.getStepInstanceId()));
        WorkflowInstance wfInst = workflowInstanceRepository.findById(stepInst.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", stepInst.getWorkflowInstanceId()));
        Vendor vendor = vendorRepository.findById(wfInst.getEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", wfInst.getEntityId()));

        var mappingOpt = mappingRepository.findByScore(vendor.getCurrentRiskScore());
        if (mappingOpt.isEmpty())
            throw new BusinessException("NO_TEMPLATE_MAPPED", "No template mapped for this vendor's risk score");
        Long templateId = mappingOpt.get().getTemplateId();

        // Create OR reuse active cycle
        VendorAssessmentCycle cycle = cycleRepository
                .findByVendorIdOrderByCycleNo(vendor.getId()).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .reduce((a, b) -> b)
                .orElse(null);

        if (cycle == null) {
            long cycleNo = cycleRepository.countByVendorId(vendor.getId()) + 1;
            cycle = VendorAssessmentCycle.builder()
                    .tenantId(tenantId).vendorId(vendor.getId())
                    .cycleNo((int) cycleNo).triggeredAt(LocalDateTime.now())
                    .triggeredBy(userId)
                    .workflowInstanceId(wfInst.getId())
                    .status("ACTIVE")
                    .build();
            cycleRepository.save(cycle);
        } else {
            cycle.setWorkflowInstanceId(wfInst.getId());
            cycleRepository.save(cycle);
        }

        boolean assessmentExists = !assessmentRepository.findByCycleId(cycle.getId()).isEmpty();
        if (assessmentExists)
            throw new BusinessException("ASSESSMENT_ALREADY_EXISTS",
                    "Assessment already instantiated for this cycle.");

        // Create VendorAssessment
        VendorAssessment assessment = VendorAssessment.builder()
                .tenantId(tenantId).cycleId(cycle.getId()).vendorId(vendor.getId())
                .templateId(templateId).status("ASSIGNED")
                .build();
        assessmentRepository.save(assessment);

        // Create AssessmentTemplateInstance (snapshot)
        AssessmentTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentTemplate", templateId));

        AssessmentTemplateInstance templateInstance = AssessmentTemplateInstance.builder()
                .tenantId(tenantId)
                .assessmentId(assessment.getId())
                .originalTemplateId(templateId)
                .templateNameSnapshot(template.getName())
                .templateVersionSnapshot(template.getVersion())
                .snapshottedAt(LocalDateTime.now())
                .build();
        templateInstanceRepository.save(templateInstance);

        // Snapshot sections → linked to templateInstance
        int questionCount = 0;
        List<TemplateSectionMapping> sectionMappings =
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNo(templateId);

        for (TemplateSectionMapping tsm : sectionMappings) {
            AssessmentSection section = sectionRepository.findById(tsm.getSectionId())
                    .orElseThrow(() -> new ResourceNotFoundException("AssessmentSection", tsm.getSectionId()));

            AssessmentSectionInstance sectionInstance = AssessmentSectionInstance.builder()
                    .templateInstanceId(templateInstance.getId())
                    .originalSectionId(section.getId())
                    .sectionNameSnapshot(section.getName())
                    .sectionOrderNo(tsm.getOrderNo())
                    .build();
            sectionInstanceRepository.save(sectionInstance);

            List<SectionQuestionMapping> questionMappings =
                    sectionQuestionMappingRepository.findBySectionIdOrderByOrderNo(section.getId());

            for (SectionQuestionMapping sqm : questionMappings) {
                AssessmentQuestion q = questionRepository.findById(sqm.getQuestionId())
                        .orElseThrow(() -> new ResourceNotFoundException("AssessmentQuestion", sqm.getQuestionId()));

                AssessmentQuestionInstance qi = AssessmentQuestionInstance.builder()
                        .assessmentId(assessment.getId())
                        .sectionInstanceId(sectionInstance.getId())
                        .originalQuestionId(q.getId())
                        .questionTextSnapshot(q.getQuestionText())
                        .responseType(q.getResponseType())
                        // Default weight to 1.0 if template was created without setting it
                        .weight(sqm.getWeight() != null ? sqm.getWeight() : 1.0)
                        .isMandatory(sqm.isMandatory())
                        .orderNo(sqm.getOrderNo())
                        // Snapshot the tag at instantiation time — full isolation from future
                        // tag changes on the library question. GuardEvaluator reads this
                        // snapshot, never joins back to assessment_questions.
                        .questionTagSnapshot(q.getQuestionTag())
                        .build();
                questionInstanceRepository.save(qi);

                questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(q.getId()).forEach(qom ->
                        optionRepository.findById(qom.getOptionId()).ifPresent(opt ->
                                optionInstanceRepository.save(AssessmentOptionInstance.builder()
                                        .questionInstanceId(qi.getId())
                                        .originalOptionId(opt.getId())
                                        .optionValue(opt.getOptionValue())
                                        .score(opt.getScore())
                                        .orderNo(qom.getOrderNo())
                                        .build())));
                questionCount++;
            }
        }

        // Auto-approve the current system task to advance the workflow
        TaskActionRequest actionReq = new TaskActionRequest();
        actionReq.setTaskInstanceId(taskId);
        actionReq.setActionType(ActionType.APPROVE);
        actionReq.setRemarks("Assessment assigned to vendor");
        WorkflowInstanceResponse wfResponse = workflowEngineService.performAction(actionReq, userId);

        List<Map<String, Object>> vendorTasks = wfResponse.getStepInstances() == null
                ? List.of()
                : wfResponse.getStepInstances().stream()
                  .flatMap(si -> si.getTaskInstances() == null ? java.util.stream.Stream.empty()
                                 : si.getTaskInstances().stream()
                                   .filter(t -> t.getStatus() == TaskStatus.PENDING))
                  .map(t -> Map.<String, Object>of(
                          "taskId",     t.getId(),
                          "assignedTo", t.getAssignedUserId()))
                  .toList();

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "assessmentId",           assessment.getId(),
                "templateInstanceId",     templateInstance.getId(),
                "cycleId",                cycle.getId(),
                "vendorId",               vendor.getId(),
                "templateId",             templateId,
                "sectionsSnapshot",       sectionMappings.size(),
                "totalQuestionsSnapshot", questionCount,
                "vendorTasksCreated",     vendorTasks)));
    }

    // ── 9.2 Get Assessment ─────────────────────────────────────────────────────
    //
    // CHANGED: assertUserHasActiveTask() called before returning data.
    // Only users with a PENDING task on the current workflow step can view the assessment.
    //
    // EXCEPTION: when assessment.status == ASSIGNED, no vendor user has been
    // delegated a task yet — the assessment was just triggered and is waiting for
    // the first org→VRM delegation. The guard is skipped so the VRM can open the
    // assign page (to pick a CISO) without being blocked. There is nothing sensitive
    // to protect at ASSIGNED status: no answers, no review data, just template structure.
    // The hard guard still applies for submitAnswer() and reviewAssessment() where
    // real write access to responses/reviews is at stake.

    @GetMapping("/v1/assessments/{assessmentId}")
    @Operation(summary = "Get assessment details with progress (vendor view)")
    public ResponseEntity<ApiResponse<VendorAssessmentResponse>> getAssessment(
            @PathVariable Long assessmentId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        VendorAssessment assessment = assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // ── READ ACCESS GUARD ────────────────────────────────────────────────
        // Skip when ASSIGNED: no vendor user has a task yet.
        // For all other statuses: allow anyone who has EVER had a task on this
        // workflow instance (PENDING, IN_PROGRESS, APPROVED, EXPIRED, DELEGATED).
        // This gives read-only access to participants after their task completes —
        // responders can still view their answers, CISO can see sections, etc.
        if (!"ASSIGNED".equals(assessment.getStatus())) {
            assertUserHasParticipated(assessment, userId, "view assessment");
        }

        Vendor vendor = vendorRepository.findById(assessment.getVendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", assessment.getVendorId()));

        String templateName = templateInstanceRepository.findByAssessmentId(assessmentId)
                .map(AssessmentTemplateInstance::getTemplateNameSnapshot)
                .orElseGet(() -> templateRepository.findById(assessment.getTemplateId())
                        .map(AssessmentTemplate::getName).orElse(null));

        List<AssessmentQuestionInstance> allQs =
                questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessmentId);
        long answered  = responseRepository.countByAssessmentId(assessmentId);
        long mandatory = allQs.stream().filter(AssessmentQuestionInstance::isMandatory).count();
        int  pct       = allQs.isEmpty() ? 0 : (int) (answered * 100 / allQs.size());

        // Fetch cycle for cycleNo and workflowInstanceId
        VendorAssessmentCycle cycle = cycleRepository.findById(assessment.getCycleId()).orElse(null);

        return ResponseEntity.ok(ApiResponse.success(VendorAssessmentResponse.builder()
                .assessmentId(assessment.getId())
                .vendorId(vendor.getId())
                .vendorName(vendor.getName())
                .templateName(templateName)
                .status(assessment.getStatus())
                .cycleNo(cycle != null ? cycle.getCycleNo() : null)
                .workflowInstanceId(cycle != null ? cycle.getWorkflowInstanceId() : null)
                .totalEarnedScore(assessment.getTotalEarnedScore())
                .totalPossibleScore(assessment.getTotalPossibleScore())
                .riskRating(assessment.getRiskRating())
                .reviewFindings(assessment.getReviewFindings())
                .submittedAt(assessment.getSubmittedAt())
                .completedAt(assessment.getCompletedAt())
                .reportUrl(assessment.getReportUrl())
                .progress(Map.of(
                        "totalQuestions",     allQs.size(),
                        "answered",           answered,
                        "mandatoryQuestions", mandatory,
                        "percentComplete",    pct))
                .sections(buildSectionInstances(assessmentId))
                .build()));
    }

    // ── List vendor assessments (logic unchanged) ──────────────────────────────

    @GetMapping("/v1/vendors/{vendorId}/assessments")
    @Operation(summary = "List assessments for a specific vendor — excludes CANCELLED by default")
    public ResponseEntity<ApiResponse<List<VendorAssessmentResponse>>> getVendorAssessments(
            @PathVariable Long vendorId,
            @RequestParam(required = false, defaultValue = "false") boolean includeCancelled) {

        // FIXED: exclude CANCELLED assessments by default.
        // Stale assessments from a Cancel & Restart are marked CANCELLED so they
        // no longer appear alongside the fresh instance. Pass ?includeCancelled=true
        // to see them (audit / admin view).
        List<VendorAssessmentResponse> result = assessmentRepository.findByVendorId(vendorId)
                .stream()
                .filter(a -> includeCancelled || !"CANCELLED".equals(a.getStatus()))
                .map(a -> {
                    String tName = templateInstanceRepository.findByAssessmentId(a.getId())
                            .map(AssessmentTemplateInstance::getTemplateNameSnapshot)
                            .orElseGet(() -> templateRepository.findById(a.getTemplateId())
                                    .map(AssessmentTemplate::getName).orElse(null));
                    long answered = responseRepository.countByAssessmentId(a.getId());
                    long total    = questionInstanceRepository.countByAssessmentId(a.getId());
                    int  pct      = total > 0 ? (int)(answered * 100 / total) : 0;
                    return VendorAssessmentResponse.builder()
                            .assessmentId(a.getId())
                            .vendorId(a.getVendorId())
                            .templateName(tName)
                            .status(a.getStatus())
                            .submittedAt(a.getSubmittedAt())
                            .progress(Map.of(
                                    "totalQuestions",  total,
                                    "answered",        answered,
                                    "percentComplete", pct))
                            .build();
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── 9.5 Submit Answer ──────────────────────────────────────────────────────
    //
    // CHANGED: assertUserHasActiveTask() called before saving any response.
    // Only users with an active task can submit answers.

    @PostMapping("/v1/assessments/{assessmentId}/responses")
    @Transactional
    @Operation(summary = "Submit or update answer to a question")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitAnswer(
            @PathVariable Long assessmentId,
            @Valid @RequestBody AnswerRequest req) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // ── STEP-GATED ACCESS GUARD ───────────────────────────────────────────
        assertUserHasActiveTask(assessment, userId, "submit answer");

        // Section-level lock: if the question's section is submitted, block edits.
        // BYPASS: if the user has an open action item for this question (revision request
        // or KashiGuard finding), the section lock is lifted — they were explicitly sent
        // back to fix this answer. Blocking them defeats the purpose of the action item.
        if (req.getQuestionInstanceId() != null) {
            questionInstanceRepository.findById(req.getQuestionInstanceId()).ifPresent(qi -> {
                sectionInstanceRepository.findById(qi.getSectionInstanceId()).ifPresent(si -> {
                    if (si.getSubmittedAt() != null) {
                        // Check if user has an open obligation for this specific question
                        boolean hasOpenObligation = !actionItemRepository.findAll(
                                ActionItemSpecification.forTenant(tenantId)
                                        .and(ActionItemSpecification.assignedTo(userId))
                                        .and(ActionItemSpecification.forEntity(
                                                ActionItem.EntityType.QUESTION_RESPONSE, qi.getId()))
                                        .and(ActionItemSpecification.open())
                        ).isEmpty();

                        if (!hasOpenObligation) {
                            throw new BusinessException("SECTION_SUBMITTED",
                                    "Section is submitted and locked. Ask CISO/VRM to reopen it.",
                                    HttpStatus.FORBIDDEN);
                        }
                        log.info("[SECTION-LOCK] Bypass for open obligation | userId={} | qi={} | si={}",
                                userId, qi.getId(), si.getId());
                    }
                });
            });
        }

        if (!"ASSIGNED".equals(assessment.getStatus()) && !"IN_PROGRESS".equals(assessment.getStatus()))
            throw new BusinessException("ASSESSMENT_NOT_EDITABLE",
                    "Cannot edit assessment in status: " + assessment.getStatus());
        if ("ASSIGNED".equals(assessment.getStatus())) {
            assessment.setStatus("IN_PROGRESS");
            assessmentRepository.save(assessment);
        }

        AssessmentResponse response = responseRepository
                .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, req.getQuestionInstanceId())
                .orElse(AssessmentResponse.builder()
                        .tenantId(tenantId)
                        .assessmentId(assessmentId)
                        .questionInstanceId(req.getQuestionInstanceId())
                        .build());

        // Multi-choice: store all selected option IDs as JSON in responseText.
        // Single-choice: store the single selectedOptionInstanceId as before.
        if (req.getSelectedOptionInstanceIds() != null && !req.getSelectedOptionInstanceIds().isEmpty()) {
            // MULTI_CHOICE — accumulate selections:
            // Merge with any previously stored selections so toggling one option
            // doesn't wipe the others.
            java.util.Set<Long> existing = new java.util.HashSet<>();
            if (response.getResponseText() != null && response.getResponseText().startsWith("[")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    Long[] arr = om.readValue(response.getResponseText(), Long[].class);
                    existing.addAll(java.util.Arrays.asList(arr));
                } catch (Exception ignored) {}
            }
            // Toggle: if already selected → remove, else → add
            for (Long optId : req.getSelectedOptionInstanceIds()) {
                if (existing.contains(optId)) existing.remove(optId);
                else existing.add(optId);
            }
            try {
                response.setResponseText(new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(existing.stream().sorted().toList()));
            } catch (Exception e) {
                response.setResponseText(existing.toString());
            }
            // Store last-toggled for scoring compatibility
            response.setSelectedOptionInstanceId(req.getSelectedOptionInstanceIds().get(0));
            // Score = sum of all selected option scores
            double totalScore = existing.stream()
                    .mapToDouble(id -> optionInstanceRepository.findById(id)
                            .map(o -> o.getScore() != null ? o.getScore() : 0.0).orElse(0.0))
                    .sum();
            response.setScoreEarned(totalScore);
        } else {
            response.setSelectedOptionInstanceId(req.getSelectedOptionInstanceId());
            response.setResponseText(req.getResponseText());
            if (req.getSelectedOptionInstanceId() != null)
                optionInstanceRepository.findById(req.getSelectedOptionInstanceId())
                        .ifPresent(o -> response.setScoreEarned(o.getScore()));
        }
        response.setSubmittedBy(userId);
        response.setSubmittedAt(LocalDateTime.now());
        // Use native INSERT ... ON DUPLICATE KEY UPDATE instead of save() + try-catch.
        //
        // WHY NOT try-catch: when save() throws DataIntegrityViolationException inside
        // a @Transactional method, Hibernate marks the session "rollback-only".
        // Any subsequent query in the same transaction (the findFirstBy... retry) then
        // triggers a flush of the broken entity (id=null) → AssertionFailure crash.
        //
        // The native upsert is atomic at the DB level — no exception, no session
        // poisoning, no race condition. If two concurrent clicks both arrive:
        //   - First: INSERT new row
        //   - Second: hits duplicate key → UPDATE existing row instead
        // Both succeed. Hibernate session stays clean throughout.
        responseRepository.upsertResponse(
                tenantId,
                assessmentId,
                req.getQuestionInstanceId(),
                response.getResponseText(),
                response.getSelectedOptionInstanceId(),
                response.getScoreEarned(),
                userId,
                response.getSubmittedAt()
        );

        // KashiGuard: evaluate answer against guard rules (async, non-blocking)
        // Uses new module-agnostic signature — no AssessmentQuestionInstance import in GuardEvaluator
        questionInstanceRepository.findById(req.getQuestionInstanceId()).ifPresent(qi -> {
            String navCtx = String.format(
                    "{\"assigneeRoute\":\"/vendor/assessments/%d/fill?openWork=1\","
                            + "\"reviewerRoute\":\"/vendor/assessments/%d/responder-review\","
                            + "\"questionInstanceId\":%d}",
                    assessment.getId(), assessment.getId(), qi.getId());
            guardEvaluator.evaluate(
                    qi.getQuestionTagSnapshot(),   // tag snapshot — guard lookup key
                    qi.getId(),
                    response.getResponseText(),
                    false,                         // file upload — extend when evidence upload built
                    response.getScoreEarned(),
                    navCtx,
                    tenantId
            );
        });

        // ── Re-answer: transition action items to PENDING_REVIEW ────────────
        // When contributor re-answers, signal "ball is in reviewer's court".
        // OPEN or IN_PROGRESS → PENDING_REVIEW (not auto-resolved — reviewer must accept).
        // Notification flows through ActionItemService lifecycle (scalable).
        final Long questionInstanceId = req.getQuestionInstanceId();
        if (questionInstanceId != null) {
            actionItemRepository.findAll(
                    ActionItemSpecification.forTenant(tenantId)
                            .and(ActionItemSpecification.assignedTo(userId))
                            .and(ActionItemSpecification.forEntity(
                                    ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                            .and(ActionItemSpecification.open())
            ).forEach(ai -> {
                if (ai.getStatus() != ActionItem.Status.OPEN
                        && ai.getStatus() != ActionItem.Status.IN_PROGRESS) return;

                // CONTRIBUTOR_ASSIGNMENT: answering the question = work done.
                // Resolve immediately — no pending review needed.
                // REVISION_REQUEST and REMEDIATION_REQUEST still go to PENDING_REVIEW below.
                if ("CONTRIBUTOR_ASSIGNMENT".equals(ai.getRemediationType())) {
                    ai.setStatus(ActionItem.Status.RESOLVED);
                    ai.setResolutionNote("Question answered by contributor");
                    ai.setResolvedAt(LocalDateTime.now());
                    ai.setResolvedBy(userId);
                    actionItemRepository.save(ai);
                    return; // skip the PENDING_REVIEW transition for this item
                }

                boolean isRemediation = "REMEDIATION_REQUEST".equals(ai.getRemediationType());

                // Transition to the appropriate pending status
                ai.setStatus(isRemediation
                        ? ActionItem.Status.PENDING_VALIDATION
                        : ActionItem.Status.PENDING_REVIEW);
                actionItemRepository.save(ai);

                // Notify the person waiting for this — always resolutionReservedFor, fall back to createdBy
                Long reviewerId = ai.getResolutionReservedFor() != null
                        ? ai.getResolutionReservedFor() : ai.getCreatedBy();
                if (reviewerId != null && reviewerId != 0L && !reviewerId.equals(userId)) {
                    String uname = userRepository.findById(userId)
                            .map(u -> {
                                String fn = u.getFirstName() != null ? u.getFirstName() : "";
                                String ln = u.getLastName()  != null ? u.getLastName()  : "";
                                String full = (fn + " " + ln).trim();
                                return full.isEmpty() ? u.getEmail() : full;
                            }).orElse("A contributor");

                    String notifType = isRemediation
                            ? "REMEDIATION_PENDING_VALIDATION"
                            : "ACTION_ITEM_PENDING_REVIEW";
                    String notifMsg  = isRemediation
                            ? uname + " submitted remediation for validation: "
                              + ai.getTitle().substring(0, Math.min(80, ai.getTitle().length()))
                            : uname + " submitted for review: "
                              + ai.getTitle().substring(0, Math.min(80, ai.getTitle().length()));

                    notificationService.send(reviewerId, notifType, notifMsg,
                            "QUESTION_RESPONSE", questionInstanceId);
                }

                log.info("[ACTION-ITEM] → {} after re-answer | id={} | qi={} | type={}",
                        ai.getStatus(), ai.getId(), questionInstanceId,
                        ai.getRemediationType() != null ? ai.getRemediationType() : "REVISION_REQUEST");
            });
        }

        // upsertResponse() is a void native query — it does not update the JPA entity's ID.
        // Re-fetch the row so we can return the real responseId to the client.
        Long responseId = responseRepository
                .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, req.getQuestionInstanceId())
                .map(AssessmentResponse::getId)
                .orElse(null);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "responseId",         responseId != null ? responseId : 0L,
                "assessmentId",       assessmentId,
                "questionInstanceId", req.getQuestionInstanceId(),
                "scoreEarned",        response.getScoreEarned() != null ? response.getScoreEarned() : 0.0)));
    }

    // ── 9.6 Add Comment (logic unchanged) ─────────────────────────────────────

    @PostMapping("/v1/assessments/responses/{responseId}/comments")
    @Operation(summary = "Add a comment to a response")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(
            @PathVariable Long responseId,
            @Valid @RequestBody CommentRequest req) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        responseRepository.findById(responseId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentResponse", responseId));

        QuestionComment comment = QuestionComment.builder()
                .responseId(responseId)
                .commentText(req.getCommentText())
                .commentedBy(userId)
                .tenantId(tenantId)
                .build();
        commentRepository.save(comment);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                CommentResponse.builder()
                        .commentId(comment.getId())
                        .commentText(comment.getCommentText())
                        .commentedBy(userId)
                        .createdAt(comment.getCreatedAt())
                        .build()));
    }

    // ── 9.7 Submit Assessment (logic unchanged) ────────────────────────────────

    @PostMapping("/v1/assessments/{assessmentId}/submit")
    @Transactional
    @Operation(summary = "Vendor submits complete assessment for review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitAssessment(
            @PathVariable Long assessmentId,
            @Valid @RequestBody AssessmentSubmitRequest req) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        VendorAssessment assessment = assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // No mandatory-question validation — CISO can publish with any number answered.
        // Partial submissions are allowed by design.
        double totalEarned = responseRepository.sumScoreByAssessmentId(assessmentId);
        assessment.setStatus("SUBMITTED");
        assessment.setSubmittedBy(userId);
        assessment.setSubmittedAt(LocalDateTime.now());
        assessment.setTotalEarnedScore(totalEarned);
        assessmentRepository.save(assessment);

        // Guard: taskId must be provided and valid
        if (req.getTaskId() == null) {
            throw new BusinessException("TASK_ID_REQUIRED",
                    "taskId is required to submit assessment and advance the workflow");
        }

        // Gap 3: fire domain event — let compound task gate handle section completion and auto-approve.
        // TaskSectionCompletionService.onSectionEvent() matches "ASSESSMENT_SUBMITTED" against
        // snap_completion_event, marks the section complete, and auto-approves the task when all
        // required sections are done. This is the correct contract for every module.
        eventPublisher.publishEvent(com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                "ASSESSMENT_SUBMITTED",
                req.getTaskId(),
                userId,
                "VENDOR_ASSESSMENT",
                assessmentId));

        // Fallback: if the step has no compound sections configured (workflow_step_sections is empty
        // for this step), onSectionEvent() finds no matching snap_completion_event and does nothing.
        // In that case fall back to direct approve so existing workflows without sections keep working.
        // Remove this block once all blueprints have sections configured.
        if (!sectionCompletionService.hasSections(req.getTaskId())) {
            log.warn("[ASSESSMENT] Step has no compound sections — falling back to direct APPROVE | taskId={}",
                    req.getTaskId());
            TaskActionRequest actionReq = new TaskActionRequest();
            actionReq.setTaskInstanceId(req.getTaskId());
            actionReq.setActionType(ActionType.APPROVE);
            actionReq.setRemarks(req.getRemarks() != null ? req.getRemarks() : "Assessment submitted");
            workflowEngineService.performAction(actionReq, userId);
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",     assessmentId,
                "status",           "SUBMITTED",
                "submittedAt",      assessment.getSubmittedAt(),
                "totalEarnedScore", totalEarned)));
    }

    // ── 10.1 Review Assessment ─────────────────────────────────────────────────
    //
    // CHANGED: assertUserHasActiveTask() called before returning data.
    // Only org reviewers with an active task on the review step can access this.

    @GetMapping("/v1/assessments/{assessmentId}/review")
    @Operation(summary = "Org reviewer retrieves full submitted assessment with scores")
    public ResponseEntity<ApiResponse<VendorAssessmentResponse>> reviewAssessment(
            @PathVariable Long assessmentId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        VendorAssessment assessment = assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // ── STEP-GATED ACCESS GUARD ───────────────────────────────────────────
        // COMPLETED assessments are read-only — allow any participant (past or present).
        // Active steps still require an active task to prevent premature access.
        if ("COMPLETED".equals(assessment.getStatus())) {
            assertUserHasParticipated(assessment, userId, "view completed assessment");
        } else {
            assertUserHasActiveTask(assessment, userId, "review assessment");
        }

        if ("SUBMITTED".equals(assessment.getStatus())) {
            assessment.setStatus("UNDER_REVIEW");
            assessmentRepository.save(assessment);
        }

        Vendor vendor = vendorRepository.findById(assessment.getVendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", assessment.getVendorId()));

        String templateName = templateInstanceRepository.findByAssessmentId(assessmentId)
                .map(AssessmentTemplateInstance::getTemplateNameSnapshot)
                .orElseGet(() -> templateRepository.findById(assessment.getTemplateId())
                        .map(AssessmentTemplate::getName).orElse(null));

        List<AssessmentQuestionInstance> allQs =
                questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessmentId);

        return ResponseEntity.ok(ApiResponse.success(VendorAssessmentResponse.builder()
                .assessmentId(assessment.getId())
                .vendorId(vendor.getId())
                .vendorName(vendor.getName())
                .templateName(templateName)
                .status(assessment.getStatus())
                .riskRating(assessment.getRiskRating())
                .openRemediationCount(assessment.getOpenRemediationCount() != null
                        ? assessment.getOpenRemediationCount().intValue() : 0)
                .progress(Map.of(
                        "totalQuestions",   allQs.size(),
                        "answered",         responseRepository.countByAssessmentId(assessmentId),
                        // Always recalculate live — DB value may be stale from vendor submission
                        "totalEarnedScore", responseRepository.sumScoreByAssessmentId(assessmentId)))
                .sections(buildSectionInstances(assessmentId))
                .build()));
    }

    // ── 10.2 List Assessments (logic unchanged) ────────────────────────────────

    @GetMapping("/v1/assessments")
    @Operation(summary = "List vendor assessments for the tenant — excludes CANCELLED by default, filterable by status")
    public ResponseEntity<ApiResponse<PaginatedResponse<VendorAssessmentResponse>>> listAssessments(
            @RequestParam Map<String, String> allParams) {

        User loggedInUser   = utilityService.getLoggedInDataContext();
        Long tenantId       = loggedInUser.getTenantId();
        // Vendor-side users are always scoped to their own vendor.
        // Org/System users see all vendors in the tenant.
        Long callerVendorId = loggedInUser.getVendorId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                VendorAssessment.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    // Vendor scoping — prevent cross-vendor data leakage
                    if (callerVendorId != null) {
                        preds.add(cb.equal(root.get("vendorId"), callerVendorId));
                    }
                    String status = allParams.get("status");
                    if (status != null) {
                        // Explicit status filter — show exactly what was requested,
                        // including CANCELLED (allows audit/admin to view stale ones)
                        preds.add(cb.equal(root.get("status"), status));
                    } else {
                        // Default: exclude CANCELLED so stale assessments from a
                        // Cancel & Restart don't pollute the active list view.
                        preds.add(cb.notEqual(root.get("status"), "CANCELLED"));
                    }
                    return preds;
                },
                (cb, root) -> Map.of("status", root.get("status"), "submittedAt", root.get("submittedAt")),
                a -> {
                    Vendor vendor   = vendorRepository.findById(a.getVendorId()).orElse(null);
                    String tName    = templateInstanceRepository.findByAssessmentId(a.getId())
                            .map(AssessmentTemplateInstance::getTemplateNameSnapshot)
                            .orElseGet(() -> templateRepository.findById(a.getTemplateId())
                                    .map(AssessmentTemplate::getName).orElse(null));
                    long answered = responseRepository.countByAssessmentId(a.getId());
                    long total    = questionInstanceRepository.countByAssessmentId(a.getId());
                    int  pct      = total > 0 ? (int) (answered * 100 / total) : 0;
                    return VendorAssessmentResponse.builder()
                            .assessmentId(a.getId())
                            .vendorId(a.getVendorId())
                            .vendorName(vendor != null ? vendor.getName() : null)
                            .templateName(tName)
                            .status(a.getStatus())
                            .submittedAt(a.getSubmittedAt())
                            .riskRating(a.getRiskRating())
                            .openRemediationCount(a.getOpenRemediationCount() != null
                                    ? a.getOpenRemediationCount().intValue() : 0)
                            .progress(Map.of(
                                    "totalQuestions",   total,
                                    "answered",         answered,
                                    "percentComplete",  pct,
                                    "totalEarnedScore", a.getTotalEarnedScore() != null
                                            ? a.getTotalEarnedScore() : 0.0))
                            .build();
                })));
    }

    /**
     * Cancel a VendorAssessment and close its parent cycle.
     *
     * PATCH /v1/assessments/{assessmentId}/cancel
     *
     * WHY THIS EXISTS:
     *   When a WorkflowInstance is cancelled and restarted (Cancel & Restart from
     *   the admin Workflow Instances page), the old VendorAssessment and its
     *   VendorAssessmentCycle are not automatically updated. They still carry their
     *   original ASSIGNED/ACTIVE statuses and appear in the list alongside the fresh
     *   instance — confusing users and blocking the new cycle from being ACTIVE.
     *
     *   This endpoint marks the stale assessment CANCELLED and closes its cycle.
     *   No data is deleted — the AssessmentTemplateInstance snapshot, SectionInstances,
     *   QuestionInstances, and Responses are all preserved for audit.
     *
     *   Once CANCELLED, the assessment is excluded from the default list view
     *   (listAssessments filters out CANCELLED unless explicitly requested).
     *
     * FLOW (called by useCancelAndRestart in WorkflowPage.jsx):
     *   1. PATCH /v1/workflow-instances/:id/cancel      → WorkflowInstance = CANCELLED
     *   2. PATCH /v1/assessments/:assessmentId/cancel   ← THIS endpoint
     *      → VendorAssessment = CANCELLED
     *      → VendorAssessmentCycle = CLOSED
     *   3. POST  /v1/workflow-instances                 → fresh instance started
     *
     * GUARD: only ASSIGNED / IN_PROGRESS assessments can be cancelled.
     *   SUBMITTED / COMPLETED / REJECTED / CANCELLED are terminal — rejected.
     */
    @PatchMapping("/v1/assessments/{assessmentId}/cancel")
    @Transactional
    @Operation(summary = "Cancel a stale assessment and close its cycle — used during Cancel & Restart")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelAssessment(
            @PathVariable Long assessmentId,
            @RequestParam(required = false) String remarks) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        VendorAssessment assessment = assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // Reject if already in a terminal status — don't touch completed work
        if (List.of("SUBMITTED", "COMPLETED", "REJECTED", "CANCELLED")
                .contains(assessment.getStatus())) {
            throw new BusinessException("ASSESSMENT_ALREADY_TERMINAL",
                    "Assessment is already in terminal status: " + assessment.getStatus(),
                    HttpStatus.CONFLICT);
        }

        String previousStatus = assessment.getStatus();
        assessment.setStatus("CANCELLED");
        assessmentRepository.save(assessment);

        // Close the parent cycle so a new one can become ACTIVE on restart
        VendorAssessmentCycle cycle = cycleRepository.findById(assessment.getCycleId()).orElse(null);
        String previousCycleStatus = null;
        if (cycle != null && "ACTIVE".equals(cycle.getStatus())) {
            previousCycleStatus = cycle.getStatus();
            cycle.setStatus("CLOSED");
            cycleRepository.save(cycle);
            log.info("[ASSESSMENT] Cycle closed | cycleId={} | vendorId={} | cycleNo={}",
                    cycle.getId(), cycle.getVendorId(), cycle.getCycleNo());
        }

        log.info("[ASSESSMENT] Cancelled | assessmentId={} | previousStatus='{}' | cycleStatus='{}' | userId={}",
                assessmentId, previousStatus, previousCycleStatus, userId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",   assessmentId,
                "status",         "CANCELLED",
                "previousStatus", previousStatus,
                "cycleId",        assessment.getCycleId(),
                "cycleStatus",    cycle != null ? cycle.getStatus() : "NOT_FOUND",
                "remarks",        remarks != null ? remarks : "Cancelled via admin restart"
        )));
    }

    // ── 10.3 Act on Task (logic unchanged) ────────────────────────────────────

    @PostMapping("/v1/workflows/tasks/{taskId}/act")
    @Transactional
    @Operation(summary = "APPROVE, REJECT, SEND_BACK, or any other action on a workflow task")
    public ResponseEntity<ApiResponse<Map<String, Object>>> actOnTask(
            @PathVariable Long taskId,
            @Valid @RequestBody TaskActionRequest req) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        req.setTaskInstanceId(taskId);
        WorkflowInstanceResponse wfResponse = workflowEngineService.performAction(req, userId);

        TaskInstance task = taskInstanceRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));
        StepInstance stepInst = stepInstanceRepository.findById(task.getStepInstanceId()).orElse(null);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "taskId",         taskId,
                "actionType",     req.getActionType().name(),
                "taskStatus",     task.getStatus().name(),
                "stepStatus",     stepInst != null ? stepInst.getStatus().name() : "",
                "instanceStatus", wfResponse.getStatus().name(),
                "processedAt",    LocalDateTime.now())));
    }

    // ── 10.6 Workflow Instance Status (logic unchanged) ───────────────────────

    @GetMapping("/v1/workflows/instances/{instanceId}/status")
    @Operation(summary = "Get current status and step history of a workflow instance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInstanceStatus(
            @PathVariable Long instanceId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        WorkflowInstance instance = workflowInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        if (!tenantId.equals(instance.getTenantId()))
            throw new BusinessException("ACCESS_DENIED", "Access denied",
                    org.springframework.http.HttpStatus.FORBIDDEN);

        // Gap 6: currentStep resolved from StepInstance snapshot below — no live blueprint read
        // use step count only from blueprint (cheap), snapshot fields for per-step data
        long totalSteps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(instance.getWorkflowId()).size();
        List<StepInstance> history  = stepInstanceRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(instanceId);
        long completed = history.stream().filter(s -> s.getStatus() == StepStatus.APPROVED).count();
        int  progress  = totalSteps > 0 ? (int) (completed * 100 / totalSteps) : 0;

        // Snapshot reads only — no live blueprint read per step row
        List<Map<String, Object>> stepHistory = history.stream().map(s -> {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("stepInstanceId", s.getId());
            entry.put("stepName",       s.getSnapName() != null ? s.getSnapName() : "");
            entry.put("stepOrder",      s.getSnapStepOrder() != null ? s.getSnapStepOrder() : 0);
            entry.put("status",         s.getStatus().name());
            entry.put("iterationCount", s.getIterationCount());
            entry.put("startedAt",      s.getStartedAt() != null ? s.getStartedAt().toString() : "");
            return entry;
        }).toList();

        // currentStep from StepInstance snapshot — no blueprint lookup
        StepInstance currentSI = instance.getCurrentStepId() != null
                ? stepInstanceRepository.findById(instance.getCurrentStepId()).orElse(null) : null;

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "workflowInstanceId", instanceId,
                "status",             instance.getStatus().name(),
                "progressPercent",    progress,
                "priority",           instance.getPriority(),
                "currentStep",        currentSI != null ? Map.of(
                        "stepId",    (Object) currentSI.getStepId(),
                        "name",      currentSI.getSnapName() != null ? currentSI.getSnapName() : "",
                        "stepOrder", currentSI.getSnapStepOrder() != null ? currentSI.getSnapStepOrder() : 0) : Map.of(),
                "stepHistory",        stepHistory)));
    }

    // ── 12.1 My Tasks ─────────────────────────────────────────────────────────
    //
    // CHANGED: Delegates to workflowEngineService.getPendingTasksForUser() /
    // getAllTasksForUser() which return enriched TaskInstanceResponse objects
    // with stepName, entityType, entityId, priority, and workflowName.
    //
    // WHY: The frontend TaskInbox calls this endpoint (GET /v1/workflows/my-tasks)
    // via useMyTasks hook. Previously this built a Map manually with only
    // taskId, stepInstanceId, stepName, status, assignedAt, entityType, entityId.
    // It was missing priority and workflowName. The enriched service mapper
    // provides all fields consistently from a single place.
    //
    // The pagination wrapper (dbRepository.findAll) is replaced with a direct
    // service call + manual pagination for the PENDING case. The status filter
    // still works: null → PENDING (default), or any TaskStatus name.

    @GetMapping("/v1/workflows/my-tasks")
    @Operation(summary = "Get all pending tasks for the current user — enriched with step, entity, and workflow context")
    public ResponseEntity<ApiResponse<List<TaskInstanceResponse>>> getMyTasks(
            @RequestParam(required = false) String status) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        log.info("[MY-TASKS] userId={} status={}", userId, status);

        List<TaskInstanceResponse> tasks;

        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            // Specific status requested — load all and filter
            TaskStatus requestedStatus;
            try {
                requestedStatus = TaskStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_STATUS",
                        "Unknown task status: " + status + ". Valid values: ALL, " +
                                Arrays.toString(TaskStatus.values()));
            }
            tasks = workflowEngineService.getAllTasksForUser(userId)
                    .stream()
                    .filter(t -> t.getStatus() == requestedStatus)
                    .toList();
        } else if (status != null && status.equalsIgnoreCase("ALL")) {
            // ALL = return every task regardless of status (full history view)
            tasks = workflowEngineService.getAllTasksForUser(userId);
        } else {
            // Default (no status param): return only active tasks for the inbox
            tasks = workflowEngineService.getPendingTasksForUser(userId);
        }

        log.info("[MY-TASKS] Returning {} tasks | userId={} | statusFilter={}",
                tasks.size(), userId, status != null ? status : "PENDING");
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Builds the full section → question → option → response tree for an assessment.
     * Works entirely from AssessmentTemplateInstance and AssessmentSectionInstance
     * (instance tables only — never touches the original library tables directly).
     * Logic unchanged.
     */
    private List<SectionInstanceResponse> buildSectionInstances(Long assessmentId) {
        AssessmentTemplateInstance ti = templateInstanceRepository
                .findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TemplateInstance for assessment", assessmentId));

        return sectionInstanceRepository
                .findByTemplateInstanceIdOrderBySectionOrderNo(ti.getId())
                .stream().map(si -> {
                    List<QuestionInstanceResponse> questions =
                            questionInstanceRepository.findBySectionInstanceIdOrderByOrderNo(si.getId())
                                    .stream().map(qi -> {
                                        var responseOpt = responseRepository
                                                .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, qi.getId());

                                        List<OptionInstanceResponse> options =
                                                optionInstanceRepository.findByQuestionInstanceIdOrderByOrderNo(qi.getId())
                                                        .stream().map(o -> OptionInstanceResponse.builder()
                                                                .optionInstanceId(o.getId())
                                                                .optionValue(o.getOptionValue())
                                                                .score(o.getScore())
                                                                .build()).toList();

                                        AnswerResponse answer = responseOpt.map(r -> {
                                            List<CommentResponse> comments =
                                                    commentRepository.findByResponseIdOrderByCreatedAt(r.getId())
                                                            .stream().map(c -> CommentResponse.builder()
                                                                    .commentId(c.getId())
                                                                    .commentText(c.getCommentText())
                                                                    .commentedBy(c.getCommentedBy())
                                                                    .createdAt(c.getCreatedAt())
                                                                    .build()).toList();
                                            // Parse multi-choice JSON array e.g. "[4257,4259]"
                                            // into selectedOptionInstanceIds so org-side views
                                            // show all selected options, not just the last-toggled one.
                                            List<Long> multiIds = new java.util.ArrayList<>();
                                            String rt = r.getResponseText();
                                            if (rt != null && rt.startsWith("[")) {
                                                try {
                                                    Long[] arr = new com.fasterxml.jackson.databind.ObjectMapper()
                                                            .readValue(rt, Long[].class);
                                                    java.util.Collections.addAll(multiIds, arr);
                                                } catch (Exception ignored) {}
                                            }
                                            return AnswerResponse.builder()
                                                    .responseId(r.getId())
                                                    .responseText(r.getResponseText())
                                                    .selectedOptionInstanceId(r.getSelectedOptionInstanceId())
                                                    .selectedOptionInstanceIds(multiIds.isEmpty() ? null : multiIds)
                                                    .scoreEarned(r.getScoreEarned())
                                                    .reviewerStatus(r.getReviewerStatus())
                                                    .submittedAt(r.getSubmittedAt())
                                                    .comments(comments)
                                                    .build();
                                        }).orElse(null);

                                        return QuestionInstanceResponse.builder()
                                                .questionInstanceId(qi.getId())
                                                .questionText(qi.getQuestionTextSnapshot())
                                                .responseType(qi.getResponseType())
                                                .weight(qi.getWeight())
                                                .mandatory(qi.isMandatory())
                                                .orderNo(qi.getOrderNo())
                                                .options(options)
                                                .currentResponse(answer)
                                                .build();
                                    }).toList();

                    return SectionInstanceResponse.builder()
                            .sectionInstanceId(si.getId())
                            .sectionName(si.getSectionNameSnapshot())
                            .sectionOrderNo(si.getSectionOrderNo())
                            .questions(questions)
                            .build();
                }).toList();
    }

    // ══════════════════════════════════════════════════════════════
    // SECTION COMPLETION ENDPOINTS — fire TaskSectionEvent per section
    // Called from the frontend when a user completes a distinct section
    // of work within a compound task step. Each fires a domain event
    // that TaskSectionCompletionService routes to the correct section.
    // ══════════════════════════════════════════════════════════════

    /** Step 3: CISO confirms all questionnaire sections are assigned */
    @PostMapping("/v1/assessments/{assessmentId}/confirm-assignment")
    @Transactional
    @Operation(summary = "Step 3: fire SECTIONS_ASSIGNED + ASSIGNMENT_CONFIRMED section events")
    public ResponseEntity<ApiResponse<Void>> confirmAssignment(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        // Fire SECTIONS_ASSIGNED first — marks the "assign sections" gate complete.
        // This section has requiresAssignment=true in the blueprint but no workflow
        // sub-tasks are created for step 3 (responder tasks come at step 4 via engine fan-out).
        // Firing the event directly here is the correct approach — onSectionEvent is idempotent.
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "SECTIONS_ASSIGNED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        // Fire ASSIGNMENT_CONFIRMED — marks the "confirm all assigned" gate complete.
        // Both required sections now complete → isAllRequiredComplete=true → auto-approve task
        // → workflow advances to step 4 → engine creates tasks for all VENDOR_RESPONDER users.
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "ASSIGNMENT_CONFIRMED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] SECTIONS_ASSIGNED + ASSIGNMENT_CONFIRMED | assessmentId={} | taskId={}",
                assessmentId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Step 4: Responder submits a section.
     * POST /v1/assessments/:id/sections/:sectionInstanceId/submit
     *
     * - Sets submitted_at on the section instance (locks it for editing)
     * - Clears contributor assignedUserId on all questions in the section
     *   (contributors lose access; responder must re-assign after reopen)
     * - If ALL sections assigned to this responder's task are now submitted,
     *   fires the compound task gate events → task auto-approves
     * - Partial answers allowed — no mandatory-question check
     */
    @PostMapping("/v1/assessments/{assessmentId}/sections/{sectionInstanceId}/submit")
    @Transactional
    @Operation(summary = "Step 4: Responder submits a section (locks it)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId,
            @RequestParam(required = false) Long taskId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        AssessmentSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SectionInstance", sectionInstanceId));

        if (section.getSubmittedAt() != null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "sectionInstanceId", sectionInstanceId,
                    "status", "ALREADY_SUBMITTED",
                    "submittedAt", section.getSubmittedAt().toString())));
        }

        // Lock the section
        section.setSubmittedAt(LocalDateTime.now());
        section.setSubmittedBy(userId);
        sectionInstanceRepository.save(section);

        // NOTE: assignedUserId is intentionally NOT cleared on submit.
        // It is preserved for audit (who was assigned to each question).
        // Access control is handled by section.submittedAt being non-null.
        // On reopen, the responder must explicitly re-assign contributors.
        List<AssessmentQuestionInstance> questions =
                questionInstanceRepository.findBySectionInstanceIdOrderByOrderNo(sectionInstanceId);

        log.info("[SECTION-SUBMIT] Section locked | sectionInstanceId={} | assessmentId={} | userId={}",
                sectionInstanceId, assessmentId, userId);

        // Auto-approve contributor sub-tasks for contributors whose ALL sections are now submitted
        // A contributor may have questions in multiple sections — only close their task
        // when the LAST section containing their questions is submitted.
        java.util.Set<Long> contributorIds = questions.stream()
                .filter(q -> q.getAssignedUserId() != null)
                .map(AssessmentQuestionInstance::getAssignedUserId)
                .collect(java.util.stream.Collectors.toSet());

        if (!contributorIds.isEmpty()) {
            AssessmentTemplateInstance tiForCheck = templateInstanceRepository
                    .findByAssessmentId(assessmentId).orElse(null);
            if (tiForCheck != null) {
                for (Long contributorId : contributorIds) {
                    // Count total sections that have at least one question assigned to this contributor
                    long totalSections = contributorSectionSubmissionRepository
                            .countDistinctSectionsWithAssignments(assessmentId, contributorId);
                    // Count sections already submitted (including this one we just submitted)
                    long submittedSections = sectionInstanceRepository
                            .findByTemplateInstanceIdOrderBySectionOrderNo(tiForCheck.getId())
                            .stream()
                            .filter(s -> s.getSubmittedAt() != null)
                            .filter(s -> questionInstanceRepository
                                    .findBySectionInstanceIdOrderByOrderNo(s.getId())
                                    .stream().anyMatch(q -> contributorId.equals(q.getAssignedUserId())))
                            .count();

                    if (submittedSections >= totalSections && totalSections > 0) {
                        // All sections containing this contributor's questions are now locked.
                        // Determine the right task closure based on whether the contributor
                        // actually submitted their answers before the responder locked the section.
                        //
                        // APPROVE  — contributor called contributorSubmitSection for every
                        //            section they had questions in → their work is complete.
                        // REJECTED — responder locked the section before contributor finished
                        //            → remove from their inbox so they aren't confused by a
                        //               task they can no longer act on.

                        // How many of the contributor's sections did they actually submit?
                        long contributorSubmittedCount = contributorSectionSubmissionRepository
                                .findByAssessmentIdAndContributorUserId(assessmentId, contributorId)
                                .size();
                        boolean contributorFinished = contributorSubmittedCount >= totalSections
                                && totalSections > 0;

                        StepInstance fillStep = stepInstanceRepository
                                .findByWorkflowInstanceIdOrderByCreatedAtAsc(
                                        cycleRepository.findById(
                                                        assessmentRepository.findById(assessmentId)
                                                                .map(va -> va.getCycleId()).orElse(0L))
                                                .map(c -> c.getWorkflowInstanceId()).orElse(0L))
                                .stream()
                                .filter(si -> si.getSnapStepAction() == com.kashi.grc.workflow.enums.StepAction.FILL
                                        && si.getStatus() == com.kashi.grc.workflow.enums.StepStatus.IN_PROGRESS)
                                .findFirst().orElse(null);

                        if (fillStep != null) {
                            taskInstanceRepository.findByStepInstanceId(fillStep.getId())
                                    .stream()
                                    .filter(t -> contributorId.equals(t.getAssignedUserId())
                                            && (t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.PENDING
                                            || t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.IN_PROGRESS))
                                    .forEach(t -> {
                                        try {
                                            if (contributorFinished) {
                                                // Contributor submitted all their sections — proper APPROVE
                                                com.kashi.grc.workflow.dto.request.TaskActionRequest req =
                                                        new com.kashi.grc.workflow.dto.request.TaskActionRequest();
                                                req.setTaskInstanceId(t.getId());
                                                req.setActionType(com.kashi.grc.workflow.enums.ActionType.APPROVE);
                                                req.setRemarks("Contributor submitted all sections — auto-approved");
                                                workflowEngineService.performAction(req, userId);
                                                log.info("[SECTION-SUBMIT] Contributor sub-task APPROVED | contributorId={} | taskId={}",
                                                        contributorId, t.getId());
                                            } else {
                                                // Responder locked section before contributor finished.
                                                // Directly set task to REJECTED so it leaves their inbox.
                                                // We do NOT use performAction(REJECT) because that would
                                                // trigger step-level rejection logic — we only want to
                                                // close this individual sub-task.
                                                t.setStatus(com.kashi.grc.workflow.enums.TaskStatus.REJECTED);
                                                t.setActedAt(java.time.LocalDateTime.now());
                                                t.setRemarks("Section locked by responder — contributor access revoked");
                                                taskInstanceRepository.save(t);
                                                log.info("[SECTION-SUBMIT] Contributor sub-task REJECTED (section locked before contributor finished) | contributorId={} | taskId={}",
                                                        contributorId, t.getId());
                                            }
                                        } catch (Exception e) {
                                            log.warn("[SECTION-SUBMIT] Could not close contributor task {}: {}",
                                                    t.getId(), e.getMessage());
                                        }
                                    });
                        }
                    }
                }
            }
        }

        // Check if ALL sections assigned to this responder are now submitted
        // If so, fire the compound task gate → task auto-approves
        if (taskId != null) {
            AssessmentTemplateInstance ti = templateInstanceRepository
                    .findByAssessmentId(assessmentId).orElse(null);
            if (ti != null) {
                List<AssessmentSectionInstance> mySections =
                        sectionInstanceRepository
                                .findByTemplateInstanceIdAndAssignedUserIdOrderBySectionOrderNo(
                                        ti.getId(), section.getAssignedUserId() != null
                                                ? section.getAssignedUserId() : userId);
                boolean allSubmitted = !mySections.isEmpty() &&
                        mySections.stream().allMatch(s -> s.getSubmittedAt() != null);

                if (allSubmitted) {
                    sectionCompletionService.markAllSectionsCompleteForTask(taskId, userId);
                    log.info("[SECTION-SUBMIT] All sections submitted — task gate fired | taskId={}", taskId);
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionInstanceId", sectionInstanceId,
                "status", "SUBMITTED",
                "submittedAt", section.getSubmittedAt().toString())));
    }

    /**
     * Step 4: CISO/VRM/ORG_ADMIN reopens a submitted section.
     * POST /v1/assessments/:id/sections/:sectionInstanceId/reopen
     *
     * - Clears submitted_at (unlocks section for editing)
     * - Does NOT restore contributor assignments (responder must re-assign)
     * - Contributor answers are preserved (only access is revoked)
     */
    @PostMapping("/v1/assessments/{assessmentId}/sections/{sectionInstanceId}/reopen")
    @Transactional
    @Operation(summary = "CISO/VRM/ORG_ADMIN reopens a submitted section")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reopenSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        User currentUser = utilityService.getLoggedInDataContext();

        // Only CISO/VRM/ORG_ADMIN can reopen
        boolean canReopen = currentUser.getRoles().stream().anyMatch(r ->
                r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.ORGANIZATION
                        || "VENDOR_CISO".equals(r.getName())
                        || "VENDOR_VRM".equals(r.getName())
                        || r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.SYSTEM);

        if (!canReopen) {
            throw new BusinessException("ACCESS_DENIED",
                    "Only CISO, VRM or Org Admin can reopen a submitted section.",
                    HttpStatus.FORBIDDEN);
        }

        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        AssessmentSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SectionInstance", sectionInstanceId));

        if (section.getSubmittedAt() == null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "sectionInstanceId", sectionInstanceId,
                    "status", "NOT_SUBMITTED")));
        }

        // Unlock — clear submitted state
        section.setSubmittedAt(null);
        section.setSubmittedBy(null);
        section.setReopenedAt(LocalDateTime.now());
        section.setReopenedBy(userId);
        sectionInstanceRepository.save(section);

        log.info("[SECTION-REOPEN] Section reopened | sectionInstanceId={} | assessmentId={} | by={}",
                sectionInstanceId, assessmentId, userId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionInstanceId", sectionInstanceId,
                "status", "REOPENED",
                "reopenedAt", section.getReopenedAt().toString())));
    }

    /**
     * POST /v1/assessments/:id/sections/:sectionInstanceId/contributor-submit
     * Contributor submits their answers for a specific section group.
     *
     * - Records submission in contributor_section_submissions
     * - Locks this section's questions as read-only for the contributor
     * - If ALL sections containing this contributor's questions are now submitted
     *   → auto-approves their sub-task (disappears from inbox)
     */
    @PostMapping("/v1/assessments/{assessmentId}/sections/{sectionInstanceId}/contributor-submit")
    @Transactional
    @Operation(summary = "Contributor submits answers for a section group")
    public ResponseEntity<ApiResponse<Map<String, Object>>> contributorSubmitSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId,
            @RequestParam(required = false) Long taskId) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        // Idempotent — already submitted
        if (contributorSectionSubmissionRepository
                .existsBySectionInstanceIdAndContributorUserId(sectionInstanceId, userId)) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "status", "ALREADY_SUBMITTED",
                    "sectionInstanceId", sectionInstanceId)));
        }

        // Verify the task belongs to this user (only when taskId is present)
        // Revision flow: contributor has no active task (APPROVED) — skip task check,
        // access was already granted by the open obligation bypass.
        if (taskId != null) {
            TaskInstance task = taskInstanceRepository.findById(taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));
            if (!userId.equals(task.getAssignedUserId())) {
                throw new BusinessException("ACCESS_DENIED", "Task does not belong to you.",
                        HttpStatus.FORBIDDEN);
            }
        }

        // Record submission
        contributorSectionSubmissionRepository.save(ContributorSectionSubmission.builder()
                .assessmentId(assessmentId)
                .sectionInstanceId(sectionInstanceId)
                .contributorUserId(userId)
                .taskInstanceId(taskId)
                .submittedAt(LocalDateTime.now())
                .build());

        log.info("[CONTRIBUTOR-SUBMIT] Section submitted | sectionInstanceId={} | userId={} | taskId={}",
                sectionInstanceId, userId, taskId);

        // Check if ALL sections with this contributor's questions are now submitted.
        // Revision flow (taskId=null): skip task-approval gate — there's no active task
        // to approve. Just record the submission and return.
        if (taskId == null) {
            log.info("[CONTRIBUTOR-SUBMIT] Revision flow — no task to approve | userId={} | si={}",
                    userId, sectionInstanceId);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "status", "SECTION_SUBMITTED",
                    "sectionInstanceId", sectionInstanceId,
                    "submittedSections", 1,
                    "totalSections",     1,
                    "taskApproved",      false)));
        }

        long totalSections = contributorSectionSubmissionRepository
                .countDistinctSectionsWithAssignments(assessmentId, userId);
        long submittedSections = contributorSectionSubmissionRepository
                .countByTaskInstanceId(taskId);

        boolean allDone = submittedSections >= totalSections && totalSections > 0;
        log.info("[CONTRIBUTOR-SUBMIT] Gate check | userId={} | submitted={} | total={} | allDone={}",
                userId, submittedSections, totalSections, allDone);

        if (allDone) {
            try {
                com.kashi.grc.workflow.dto.request.TaskActionRequest req =
                        new com.kashi.grc.workflow.dto.request.TaskActionRequest();
                req.setTaskInstanceId(taskId);
                req.setActionType(com.kashi.grc.workflow.enums.ActionType.APPROVE);
                req.setRemarks("Contributor submitted all assigned section answers");
                workflowEngineService.performAction(req, userId);
                log.info("[CONTRIBUTOR-SUBMIT] Sub-task approved | taskId={}", taskId);
            } catch (BusinessException e) {
                if (!"TASK_TERMINAL".equals(e.getErrorCode())) throw e;
            }
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status",          allDone ? "TASK_APPROVED" : "SECTION_SUBMITTED",
                "sectionInstanceId", sectionInstanceId,
                "submittedSections", submittedSections,
                "totalSections",     totalSections,
                "taskApproved",      allDone)));
    }

    /**
     * GET /v1/assessments/:id/sections/:sectionInstanceId/contributor-status
     * Returns which section groups the contributor has submitted.
     * Used by the fill page to show submitted state per section in contributor mode.
     */
    @GetMapping("/v1/assessments/{assessmentId}/contributor-section-status")
    @Transactional(readOnly = true)
    @Operation(summary = "Contributor: get submission status for each of their sections")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getContributorSectionStatus(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        List<Map<String, Object>> result =
                contributorSectionSubmissionRepository
                        .findByAssessmentIdAndContributorUserId(assessmentId, userId)
                        .stream()
                        .map(s -> {
                            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("sectionInstanceId", s.getSectionInstanceId());
                            m.put("submittedAt", s.getSubmittedAt().toString());
                            return m;
                        })
                        .toList();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * GET /v1/assessments/:id/sections/status
     * Returns all section instances with assignment + submission status.
     * Used by CISO/VRM/ORG_ADMIN for the management overview (VendorDetailPage tab)
     * and FillPage observer reopen buttons.
     */
    @GetMapping("/v1/assessments/{assessmentId}/sections/status")
    @Transactional(readOnly = true)
    @Operation(summary = "CISO/VRM/ORG_ADMIN: all sections with assignment and submission status")
    public ResponseEntity<ApiResponse<List<SectionInstanceResponse>>> getSectionsStatus(
            @PathVariable Long assessmentId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        User currentUser = utilityService.getLoggedInDataContext();

        boolean canView = currentUser.getRoles().stream().anyMatch(r ->
                r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.ORGANIZATION
                        || "VENDOR_CISO".equals(r.getName())
                        || "VENDOR_VRM".equals(r.getName())
                        || r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.SYSTEM);

        if (!canView) {
            throw new BusinessException("ACCESS_DENIED",
                    "Only CISO, VRM or Org Admin can view section status.",
                    HttpStatus.FORBIDDEN);
        }

        AssessmentTemplateInstance ti = templateInstanceRepository
                .findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateInstance for assessment", assessmentId));

        // Bulk-load user names for all assigned + submitted users
        List<AssessmentSectionInstance> sections =
                sectionInstanceRepository.findByTemplateInstanceIdOrderBySectionOrderNo(ti.getId());

        java.util.Set<Long> userIds = new java.util.HashSet<>();
        sections.forEach(s -> {
            if (s.getAssignedUserId() != null) userIds.add(s.getAssignedUserId());
            if (s.getSubmittedBy()    != null) userIds.add(s.getSubmittedBy());
            if (s.getReopenedBy()     != null) userIds.add(s.getReopenedBy());
        });
        Map<Long, String> nameMap = userRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.kashi.grc.usermanagement.domain.User::getId,
                        u -> {
                            String fn = u.getFirstName() != null ? u.getFirstName() : "";
                            String ln = u.getLastName()  != null ? u.getLastName()  : "";
                            String full = (fn + " " + ln).trim();
                            return full.isEmpty() ? u.getEmail() : full;
                        }));

        List<SectionInstanceResponse> result = sections.stream().map(s -> {
            long total    = questionInstanceRepository.countBySectionInstanceId(s.getId());
            long answered = responseRepository.countByAssessmentIdAndSectionInstanceId(
                    assessmentId, s.getId());

            return SectionInstanceResponse.builder()
                    .sectionInstanceId(s.getId())
                    .sectionName(s.getSectionNameSnapshot())
                    .sectionOrderNo(s.getSectionOrderNo())
                    .assignedUserId(s.getAssignedUserId())
                    .assignedUserName(nameMap.get(s.getAssignedUserId()))
                    .submittedAt(s.getSubmittedAt())
                    .submittedBy(s.getSubmittedBy())
                    .submittedByName(nameMap.get(s.getSubmittedBy()))
                    .reopenedAt(s.getReopenedAt())
                    .questions(null) // status view only — no questions loaded
                    .build();
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Step 5a: Responder marks they have reviewed all answers in a section.
     * Fires ANSWERS_REVIEWED event which completes the first task section checkpoint.
     * Called automatically when the responder opens the review page.
     * Idempotent — safe to call multiple times.
     */
    @PostMapping("/v1/assessments/{assessmentId}/mark-section-complete")
    @Transactional
    @Operation(summary = "Step 5a: fire ANSWERS_REVIEWED section event")
    public ResponseEntity<ApiResponse<Void>> markSectionComplete(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        try {
            eventPublisher.publishEvent(
                    com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                            "ANSWERS_REVIEWED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
            log.info("[SECTION-EVENT] ANSWERS_REVIEWED | assessmentId={} | taskId={}", assessmentId, taskId);
        } catch (Exception e) {
            // Idempotent — ignore if section already marked or event doesn't match
            log.debug("[SECTION-EVENT] ANSWERS_REVIEWED skipped | assessmentId={} | reason={}",
                    assessmentId, e.getMessage());
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Step 5: Responder publishes their section for CISO review.
     * Fires SECTION_PUBLISHED → completes "Publish section" checkpoint.
     * Called from VendorAssessmentResponderReviewPage when taskRole = RESPONDER step.
     */
    @PostMapping("/v1/assessments/{assessmentId}/publish-section")
    @Operation(summary = "Step 5: fire SECTION_PUBLISHED section event")
    public ResponseEntity<ApiResponse<Void>> publishSection(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "SECTION_PUBLISHED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] SECTION_PUBLISHED | assessmentId={} | taskId={}", assessmentId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Step 6: Vendor CISO submits the completed assessment to the org.
     *
     * 1. BULK GUARD SWEEP — publishes ModuleSubmitEvent so GuardEvaluationListener
     *    evaluates every question against guard rules after commit. Catches
     *    unanswered questions (TEXT_EMPTY / FILE_NOT_UPLOADED / ANY_ANSWER rules).
     *    Generic: any module uses the same listener — zero code duplication.
     *
     * 2. WORKFLOW EVENTS — fires CISO_REVIEW_COMPLETE + ASSESSMENT_SUBMITTED.
     */
    @PostMapping("/v1/assessments/{assessmentId}/ciso-submit")
    @Operation(summary = "Step 6: guard sweep + fire CISO_REVIEW_COMPLETE + ASSESSMENT_SUBMITTED")
    public ResponseEntity<ApiResponse<Void>> cisoSubmit(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // ── 1. Build QuestionContext list for generic guard sweep ─────────────
        // Map questionInstanceId → latest response (null if never answered)
        java.util.Map<Long, com.kashi.grc.assessment.domain.AssessmentResponse> responseMap =
                responseRepository.findByAssessmentId(assessmentId).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.kashi.grc.assessment.domain.AssessmentResponse::getQuestionInstanceId,
                                r -> r, (a, b) -> b));

        java.util.List<ModuleSubmitEvent.QuestionContext> contexts =
                questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessmentId)
                        .stream()
                        .map(qi -> {
                            com.kashi.grc.assessment.domain.AssessmentResponse r =
                                    responseMap.get(qi.getId());
                            String navCtx = String.format(
                                    "{\"assigneeRoute\":\"/vendor/assessments/%d/fill?openWork=1\","
                                            + "\"reviewerRoute\":\"/vendor/assessments/%d/responder-review\","
                                            + "\"questionInstanceId\":%d}",
                                    assessmentId, assessmentId, qi.getId());
                            return new ModuleSubmitEvent.QuestionContext(
                                    qi.getQuestionTagSnapshot(),
                                    qi.getId(),
                                    r != null ? r.getResponseText() : null,
                                    false,  // file upload check — extend when evidence upload built
                                    r != null ? r.getScoreEarned() : null,
                                    navCtx
                            );
                        })
                        .toList();

        // Publish generic event — GuardEvaluationListener handles sweep after commit
        eventPublisher.publishEvent(new ModuleSubmitEvent(
                "VENDOR_ASSESSMENT", assessmentId, taskId, userId, tenantId, contexts));

        // ── 2. Workflow section events ────────────────────────────────────────
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "CISO_REVIEW_COMPLETE", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "ASSESSMENT_SUBMITTED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] CISO_REVIEW_COMPLETE + ASSESSMENT_SUBMITTED | assessmentId={} | taskId={}",
                assessmentId, taskId);

        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Step 7: Org Admin assigns the assessment review to an Org CISO.
     * stepAction=ASSIGN — the actor delegates via the workflow DELEGATE action.
     * This endpoint fires ORG_CISO_ASSIGNED to complete the single section checkpoint
     * and auto-approve the task, advancing to step 8 (Org CISO assigns to reviewers).
     *
     * Called after the org admin selects a CISO and confirms the delegation.
     */
    @PostMapping("/v1/assessments/{assessmentId}/assign-org-ciso")
    @Operation(summary = "Step 7: fire ORG_CISO_ASSIGNED section event")
    public ResponseEntity<ApiResponse<Void>> assignOrgCiso(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "ORG_CISO_ASSIGNED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] ORG_CISO_ASSIGNED | assessmentId={} | taskId={}", assessmentId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Step 8: Org CISO confirms all reviewer assignments are done */
    @PostMapping("/v1/assessments/{assessmentId}/confirm-reviewer-assignment")
    @Transactional
    @Operation(summary = "Step 8: fire REVIEWERS_ASSIGNED + REVIEWER_ASSIGNMENT_CONFIRMED section events")
    public ResponseEntity<ApiResponse<Void>> confirmReviewerAssignment(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        // Same pattern as step 3 — fire both required section events so the gate passes.
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "REVIEWERS_ASSIGNED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "REVIEWER_ASSIGNMENT_CONFIRMED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] REVIEWERS_ASSIGNED + REVIEWER_ASSIGNMENT_CONFIRMED | assessmentId={} | taskId={}",
                assessmentId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Step 9: Reviewer assigns a question to a review assistant.
     * PUT /v1/assessments/{assessmentId}/questions/{questionInstanceId}/reviewer-assign
     *
     * Writes to reviewerAssignedUserId — a SEPARATE column from assignedUserId
     * (which holds the vendor contributor assignment from step 4).
     *
     * WHY SEPARATE:
     *   assignedUserId is the vendor contributor — set in step 4, drives the contributor
     *   inbox, sub-task creation, and re-answer flows. Overwriting it with a review
     *   assistant would destroy audit trail, break Send Back flows, and corrupt
     *   contributor-inbox queries across new assessment cycles.
     *
     *   reviewerAssignedUserId is purely an org-side review delegation record.
     *   It survives Send Back, multi-cycle assessments, and future reporting queries.
     */
    @PutMapping("/v1/assessments/{assessmentId}/questions/{questionInstanceId}/reviewer-assign")
    @Transactional
    @Operation(summary = "Step 9: Reviewer assigns question to a review assistant (org-side only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewerAssignQuestion(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId,
            @RequestBody Map<String, Long> body) {

        Long reviewAssistantId = body.get("userId");
        if (reviewAssistantId == null)
            throw new com.kashi.grc.common.exception.ValidationException("userId is required");

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        if (!qi.getAssessmentId().equals(assessmentId))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "QuestionInstance does not belong to assessment " + assessmentId);

        Long prevAssistantId = qi.getReviewerAssignedUserId();
        qi.setReviewerAssignedUserId(reviewAssistantId);
        questionInstanceRepository.save(qi);

        Long tenantId  = utilityService.getLoggedInDataContext().getTenantId();
        Long assignerId = utilityService.getLoggedInDataContext().getId();

        // Close previous assistant's open REVIEWER_ASSIGNMENT item for this question
        if (prevAssistantId != null && !prevAssistantId.equals(reviewAssistantId)) {
            actionItemRepository.findAll(
                            ActionItemSpecification.forTenant(tenantId)
                                    .and(ActionItemSpecification.forEntity(
                                            ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                                    .and(ActionItemSpecification.open())
                    ).stream()
                    .filter(ai -> "REVIEWER_ASSIGNMENT".equals(ai.getRemediationType())
                            && prevAssistantId.equals(ai.getAssignedTo()))
                    .forEach(ai -> {
                        ai.setStatus(ActionItem.Status.RESOLVED);
                        ai.setResolutionNote("Reassigned to another review assistant");
                        ai.setResolvedAt(LocalDateTime.now());
                        ai.setResolvedBy(assignerId);
                        actionItemRepository.save(ai);
                    });
        }

        // Create ActionItem for the review assistant — their inbox entry
        // navContext → scrolls directly to this question on the review page
        ActionItem item = ActionItem.builder()
                .tenantId(tenantId)
                .createdBy(assignerId)
                .assignedTo(reviewAssistantId)
                .sourceType(ActionItem.SourceType.SYSTEM)
                .sourceId(questionInstanceId)
                .entityType(ActionItem.EntityType.QUESTION_RESPONSE)
                .entityId(questionInstanceId)
                .title(qi.getQuestionTextSnapshot()
                        .substring(0, Math.min(80, qi.getQuestionTextSnapshot().length())))
                .status(ActionItem.Status.OPEN)
                .priority(ActionItem.Priority.MEDIUM)
                .remediationType("REVIEWER_ASSIGNMENT")
                .build();
        actionItemRepository.save(item);

        String navCtx = String.format(
                "{\"assigneeRoute\":\"/assessments/%d/review?questionInstanceId=%d\"" +
                        ",\"reviewerRoute\":\"/assessments/%d/review\"" +
                        ",\"questionInstanceId\":%d,\"assessmentId\":%d}",
                assessmentId, questionInstanceId,
                assessmentId, questionInstanceId, assessmentId);
        item.setNavContext(navCtx);
        actionItemRepository.save(item);

        notificationService.send(reviewAssistantId, "QUESTION_ASSIGNED_FOR_REVIEW",
                "You have been assigned a question to evaluate",
                "QUESTION_RESPONSE", questionInstanceId);

        String name = userRepository.findById(reviewAssistantId)
                .map(u -> {
                    String fn = u.getFirstName() != null ? u.getFirstName() : "";
                    String ln = u.getLastName()  != null ? u.getLastName()  : "";
                    String full = (fn + " " + ln).trim();
                    return full.isEmpty() ? u.getEmail() : full;
                }).orElse(null);

        log.info("[REVIEWER-ASSIGN] qi={} → userId={} | assessmentId={} | actionItemId={}",
                questionInstanceId, reviewAssistantId, assessmentId, item.getId());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",             assessmentId,
                "questionInstanceId",       questionInstanceId,
                "reviewerAssignedUserId",   reviewAssistantId,
                "reviewerAssignedUserName", name != null ? name : ""
        )));
    }

    /**
     * Step 9: Reviewer removes their review assistant assignment for a question.
     * DELETE /v1/assessments/{assessmentId}/questions/{questionInstanceId}/reviewer-assign
     */
    @DeleteMapping("/v1/assessments/{assessmentId}/questions/{questionInstanceId}/reviewer-assign")
    @Transactional
    @Operation(summary = "Step 9: Remove reviewer assistant assignment from a question")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewerUnassignQuestion(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId) {

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        if (!qi.getAssessmentId().equals(assessmentId))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "QuestionInstance does not belong to assessment " + assessmentId);

        qi.setReviewerAssignedUserId(null);
        questionInstanceRepository.save(qi);

        Long tenantId   = utilityService.getLoggedInDataContext().getTenantId();
        Long unassignerId = utilityService.getLoggedInDataContext().getId();

        // Close any open REVIEWER_ASSIGNMENT items for this question
        if (qi.getReviewerAssignedUserId() != null) {
            actionItemRepository.findAll(
                            ActionItemSpecification.forTenant(tenantId)
                                    .and(ActionItemSpecification.forEntity(
                                            ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                                    .and(ActionItemSpecification.open())
                    ).stream()
                    .filter(ai -> "REVIEWER_ASSIGNMENT".equals(ai.getRemediationType()))
                    .forEach(ai -> {
                        ai.setStatus(ActionItem.Status.RESOLVED);
                        ai.setResolutionNote("Review assistant assignment removed");
                        ai.setResolvedAt(LocalDateTime.now());
                        ai.setResolvedBy(unassignerId);
                        actionItemRepository.save(ai);
                    });
        }

        log.info("[REVIEWER-UNASSIGN] qi={} | assessmentId={}", questionInstanceId, assessmentId);

        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("assessmentId",       assessmentId);
        resp.put("questionInstanceId", questionInstanceId);
        resp.put("reviewerAssignedUserId", null);
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    /**
     * Step 9: Reviewer saves their evaluation verdict for a single question.
     * PUT /v1/assessments/{assessmentId}/questions/{questionInstanceId}/reviewer-eval
     *
     * Persists PASS / PARTIAL / FAIL to AssessmentResponse.reviewerStatus.
     * Called on every eval button click so verdicts survive page refresh.
     * Idempotent — always overwrites with the latest value.
     */
    @PutMapping("/v1/assessments/{assessmentId}/questions/{questionInstanceId}/reviewer-eval")
    @Transactional
    @Operation(summary = "Save reviewer evaluation verdict for a question (PASS/PARTIAL/FAIL)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveReviewerEval(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId,
            @RequestBody Map<String, String> body) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        String verdict = body.get("verdict");
        if (verdict == null || !java.util.Set.of("PASS","PARTIAL","FAIL").contains(verdict.toUpperCase())) {
            throw new com.kashi.grc.common.exception.ValidationException(
                    "verdict must be PASS, PARTIAL, or FAIL");
        }

        AssessmentResponse response = responseRepository
                .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, questionInstanceId)
                .orElseGet(() -> {
                    // Question may not have a response yet (unanswered) — create a shell record
                    Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
                    AssessmentResponse r = AssessmentResponse.builder()
                            .tenantId(tenantId)
                            .assessmentId(assessmentId)
                            .questionInstanceId(questionInstanceId)
                            .build();
                    return responseRepository.save(r);
                });

        response.setReviewerStatus(verdict.toUpperCase());
        responseRepository.save(response);

        // Auto-resolve open REVIEWER_ASSIGNMENT action items for this question
        // when the review assistant saves their evaluation verdict.
        // Same pattern as CONTRIBUTOR_ASSIGNMENT resolution in submitAnswer.
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        actionItemRepository.findAll(
                        ActionItemSpecification.forTenant(tenantId)
                                .and(ActionItemSpecification.assignedTo(userId))
                                .and(ActionItemSpecification.forEntity(
                                        ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                                .and(ActionItemSpecification.open())
                ).stream()
                .filter(ai -> "REVIEWER_ASSIGNMENT".equals(ai.getRemediationType()))
                .forEach(ai -> {
                    ai.setStatus(ActionItem.Status.RESOLVED);
                    ai.setResolutionNote("Question evaluated by review assistant");
                    ai.setResolvedAt(LocalDateTime.now());
                    ai.setResolvedBy(userId);
                    actionItemRepository.save(ai);
                });

        log.info("[REVIEWER-EVAL] {} | assessmentId={} | qi={} | by={}",
                verdict.toUpperCase(), assessmentId, questionInstanceId, userId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "questionInstanceId", questionInstanceId,
                "reviewerStatus",     verdict.toUpperCase()
        )));
    }

    /**
     * Step 9: Reviewer marks their evaluation complete (all questions scored + commented).
     *
     * TASK CLOSURE PATTERN — mirrors submitAssessment exactly:
     *
     *   IF step has workflow_step_sections configured (task_section_completions rows exist):
     *     Fire REVIEW_COMMENTS_ADDED event → onSectionEvent marks section complete
     *     → isAllRequiredComplete → autoApproveTask closes the individual reviewer task.
     *
     *   IF step has NO workflow_step_sections (no rows in task_section_completions):
     *     Event fires but onSectionEvent finds no matching snap_completion_event → no-op.
     *     Fall back to direct performAction(APPROVE) — closes the reviewer's task immediately.
     *
     * WHY THIS MATTERS:
     *   Step 9 has N reviewer tasks (one per reviewer). Each reviewer submits independently.
     *   This closes THEIR task only. The step itself stays IN_PROGRESS until ALL reviewer
     *   tasks are approved. That is correct — the step advances to step 10 when the last
     *   reviewer submits. This mirrors how vendor contributor tasks work in step 4.
     */
    @PostMapping("/v1/assessments/{assessmentId}/complete-reviewer-evaluation")
    @Transactional
    @Operation(summary = "Step 9: mark reviewer evaluation complete — closes individual reviewer task")
    public ResponseEntity<ApiResponse<Void>> completeReviewerEvaluation(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // Fire the section event — works when step 9 has workflow_step_sections configured.
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "REVIEW_COMMENTS_ADDED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));

        // Fallback: if step 9 has no sections configured, the event does nothing.
        // Fall back to direct APPROVE — closes the reviewer's own task.
        // This is the same pattern as submitAssessment's hasSections fallback.
        if (!sectionCompletionService.hasSections(taskId)) {
            log.warn("[REVIEWER-EVAL] No sections configured for task {} — falling back to direct APPROVE", taskId);
            com.kashi.grc.workflow.dto.request.TaskActionRequest req =
                    new com.kashi.grc.workflow.dto.request.TaskActionRequest();
            req.setTaskInstanceId(taskId);
            req.setActionType(com.kashi.grc.workflow.enums.ActionType.APPROVE);
            req.setRemarks("Reviewer evaluation complete — task approved");
            try {
                workflowEngineService.performAction(req, userId);
            } catch (BusinessException e) {
                // TASK_TERMINAL: already approved (idempotent — ignore)
                if (!"TASK_TERMINAL".equals(e.getErrorCode())) throw e;
            }
        }

        log.info("[REVIEWER-EVAL] Complete | assessmentId={} | taskId={} | by={}", assessmentId, taskId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Step 10: Reviewer marks section scores consolidated */
    @PostMapping("/v1/assessments/{assessmentId}/consolidate-scores")
    @Transactional
    @Operation(summary = "Step 10: recalculate total score, persist, and fire SCORES_CONSOLIDATED event")
    public ResponseEntity<ApiResponse<Void>> consolidateScores(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // Recalculate total earned score from live response data
        // This corrects any stale/zero value from vendor submission time
        double liveScore = responseRepository.sumScoreByAssessmentId(assessmentId);
        assessment.setTotalEarnedScore(liveScore);
        assessmentRepository.save(assessment);
        log.info("[CONSOLIDATE] totalEarnedScore={} | assessmentId={}", liveScore, assessmentId);

        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "SCORES_CONSOLIDATED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] SCORES_CONSOLIDATED | assessmentId={} | taskId={}", assessmentId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Step 10: Reviewer saves consolidated findings text */
    @PostMapping("/v1/assessments/{assessmentId}/document-findings")
    @Transactional
    @Operation(summary = "Step 10: save findings text and fire FINDINGS_DOCUMENTED event")
    public ResponseEntity<ApiResponse<Void>> documentFindings(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        String findings = body != null ? body.getOrDefault("findings", "") : "";
        if (!findings.isBlank()) {
            assessment.setReviewFindings(findings);
            assessmentRepository.save(assessment);
            log.info("[FINDINGS] Saved {} chars of findings | assessmentId={}", findings.length(), assessmentId);
        }
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "FINDINGS_DOCUMENTED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] FINDINGS_DOCUMENTED | assessmentId={} | taskId={}", assessmentId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Step 11: Org CISO approves the consolidated review */
    @PostMapping("/v1/assessments/{assessmentId}/ciso-approve")
    @Transactional
    @Operation(summary = "Step 11: fire CISO_APPROVED section event")
    public ResponseEntity<ApiResponse<Void>> cisoApprove(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "CISO_APPROVED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        log.info("[SECTION-EVENT] CISO_APPROVED | assessmentId={} | taskId={}", assessmentId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Step 11: Org CISO assigns a risk rating to the vendor */
    @PostMapping("/v1/assessments/{assessmentId}/risk-rating")
    @Transactional
    @Operation(summary = "Step 11: save risk rating and fire RISK_RATING_ASSIGNED event")
    public ResponseEntity<ApiResponse<Void>> assignRiskRating(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId,
            @RequestParam String riskRating) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        java.util.Set<String> validRatings = java.util.Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        if (!validRatings.contains(riskRating.toUpperCase())) {
            throw new BusinessException("INVALID_RISK_RATING",
                    "riskRating must be one of: LOW, MEDIUM, HIGH, CRITICAL");
        }
        assessment.setRiskRating(riskRating.toUpperCase());
        assessment.setTotalEarnedScore(responseRepository.sumScoreByAssessmentId(assessmentId));
        assessmentRepository.save(assessment);
        log.info("[RISK-RATING] {} | assessmentId={} | by={}", riskRating.toUpperCase(), assessmentId, userId);
        eventPublisher.publishEvent(
                com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                        "RISK_RATING_ASSIGNED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ══════════════════════════════════════════════════════════════
    // SECTION + QUESTION ASSIGNMENT (logic unchanged)
    // ══════════════════════════════════════════════════════════════

    @PutMapping("/v1/assessments/{assessmentId}/sections/{sectionInstanceId}/assign")
    @Transactional
    @Operation(summary = "Step 4 — CISO assigns section to Responder")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId,
            @RequestBody Map<String, Long> body) {

        Long userId = body.get("userId");
        if (userId == null)
            throw new com.kashi.grc.common.exception.ValidationException("userId is required");

        AssessmentSectionInstance si = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SectionInstance", sectionInstanceId));

        AssessmentTemplateInstance ti = templateInstanceRepository
                .findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateInstance for assessment", assessmentId));

        if (!si.getTemplateInstanceId().equals(ti.getId()))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "SectionInstance does not belong to assessment " + assessmentId);

        si.setAssignedUserId(userId);
        sectionInstanceRepository.save(si);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",      assessmentId,
                "sectionInstanceId", sectionInstanceId,
                "assignedUserId",    userId,
                "message",           "Section assigned successfully"
        )));
    }

    @DeleteMapping("/v1/assessments/{assessmentId}/questions/{questionInstanceId}/assign")
    @Transactional
    @Operation(summary = "Step 4 — Responder unassigns a question from its contributor")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unassignQuestion(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId) {

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        if (!qi.getAssessmentId().equals(assessmentId))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "QuestionInstance does not belong to assessment " + assessmentId);

        Long prevContributorId = qi.getAssignedUserId();
        qi.setAssignedUserId(null);
        questionInstanceRepository.save(qi);

        if (prevContributorId != null) {
            Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
            Long unassignerId = utilityService.getLoggedInDataContext().getId();

            // Close the contributor's open CONTRIBUTOR_ASSIGNMENT ActionItem for this question
            actionItemRepository.findAll(
                            ActionItemSpecification.forTenant(tenantId)
                                    .and(ActionItemSpecification.forEntity(
                                            ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                                    .and(ActionItemSpecification.open())
                    ).stream()
                    .filter(ai -> "CONTRIBUTOR_ASSIGNMENT".equals(ai.getRemediationType())
                            && prevContributorId.equals(ai.getAssignedTo()))
                    .forEach(ai -> {
                        ai.setStatus(ActionItem.Status.RESOLVED);
                        ai.setResolutionNote("Contributor unassigned from question");
                        ai.setResolvedAt(LocalDateTime.now());
                        ai.setResolvedBy(unassignerId);
                        actionItemRepository.save(ai);
                    });

            // Legacy sub-task cleanup
            long remaining = questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessmentId)
                    .stream().filter(q -> prevContributorId.equals(q.getAssignedUserId())).count();
            if (remaining == 0) {
                closeContributorTask(assessmentId, prevContributorId,
                        "All assigned questions removed — contributor access revoked");
            }
        }

        log.info("[UNASSIGN] Question unassigned | questionInstanceId={} | assessmentId={}",
                questionInstanceId, assessmentId);

        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("assessmentId",       assessmentId);
        resp.put("questionInstanceId", questionInstanceId);
        resp.put("assignedUserId",     null);
        resp.put("message",            "Contributor unassigned");
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @PutMapping("/v1/assessments/{assessmentId}/questions/{questionInstanceId}/assign")
    @Transactional
    @Operation(summary = "Step 4 — Responder assigns a single question to a Contributor")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignQuestion(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId,
            @RequestBody Map<String, Long> body) {

        Long contributorId = body.get("userId");
        if (contributorId == null)
            throw new com.kashi.grc.common.exception.ValidationException("userId is required");

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        if (!qi.getAssessmentId().equals(assessmentId))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "QuestionInstance does not belong to assessment " + assessmentId);

        Long assignerId = utilityService.getLoggedInDataContext().getId();
        doAssignQuestion(qi, contributorId, assessmentId, assignerId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",       assessmentId,
                "questionInstanceId", questionInstanceId,
                "assignedUserId",     contributorId,
                "message",            "Question assigned to contributor"
        )));
    }

    /**
     * Batch-assign multiple questions to a contributor in one call.
     * PUT /v1/assessments/{assessmentId}/questions/assign-batch
     * Body: { "userId": 123, "questionInstanceIds": [1, 2, 3] }
     */
    @PutMapping("/v1/assessments/{assessmentId}/questions/assign-batch")
    @Transactional
    @Operation(summary = "Step 4 — Responder assigns multiple questions to a Contributor at once")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignQuestionsBatch(
            @PathVariable Long assessmentId,
            @RequestBody Map<String, Object> body) {

        Long contributorId = body.get("userId") instanceof Number n ? n.longValue() : null;
        if (contributorId == null)
            throw new com.kashi.grc.common.exception.ValidationException("userId is required");

        @SuppressWarnings("unchecked")
        java.util.List<Integer> rawIds = (java.util.List<Integer>) body.get("questionInstanceIds");
        if (rawIds == null || rawIds.isEmpty())
            throw new com.kashi.grc.common.exception.ValidationException("questionInstanceIds is required");

        Long assignerId = utilityService.getLoggedInDataContext().getId();
        java.util.List<Long> questionIds = rawIds.stream().map(Integer::longValue).toList();

        for (Long qId : questionIds) {
            AssessmentQuestionInstance qi = questionInstanceRepository.findById(qId)
                    .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", qId));
            if (!qi.getAssessmentId().equals(assessmentId))
                throw new com.kashi.grc.common.exception.ValidationException(
                        "QuestionInstance " + qId + " does not belong to assessment " + assessmentId);
            doAssignQuestion(qi, contributorId, assessmentId, assignerId);
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",    assessmentId,
                "assignedUserId",  contributorId,
                "assigned",        questionIds.size(),
                "message",         questionIds.size() + " question(s) assigned to contributor"
        )));
    }

    /**
     * Shared logic: assign one question to a contributor and ensure they have
     * a sub-task on the current FILL step so it appears in their inbox.
     */
    private void doAssignQuestion(AssessmentQuestionInstance qi, Long contributorId,
                                  Long assessmentId, Long assignerId) {
        if (contributorId.equals(qi.getAssignedUserId())) return; // idempotent

        Long prevContributorId = qi.getAssignedUserId();
        qi.setAssignedUserId(contributorId);
        questionInstanceRepository.save(qi);

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // ── Reassignment cleanup ──────────────────────────────────────────────────
        if (prevContributorId != null && !prevContributorId.equals(contributorId)) {

            // Close the previous contributor's open CONTRIBUTOR_ASSIGNMENT item
            // for this specific question
            actionItemRepository.findAll(
                            ActionItemSpecification.forTenant(tenantId)
                                    .and(ActionItemSpecification.forEntity(
                                            ActionItem.EntityType.QUESTION_RESPONSE, qi.getId()))
                                    .and(ActionItemSpecification.open())
                    ).stream()
                    .filter(ai -> "CONTRIBUTOR_ASSIGNMENT".equals(ai.getRemediationType())
                            && prevContributorId.equals(ai.getAssignedTo()))
                    .forEach(ai -> {
                        ai.setStatus(ActionItem.Status.RESOLVED);
                        ai.setResolutionNote("Reassigned to another contributor");
                        ai.setResolvedAt(LocalDateTime.now());
                        ai.setResolvedBy(assignerId);
                        actionItemRepository.save(ai);
                    });

            // Legacy sub-task cleanup — close if previous contributor has no
            // remaining questions anywhere in this assessment
            long remaining = questionInstanceRepository
                    .findByAssessmentIdOrderByOrderNo(assessmentId)
                    .stream()
                    .filter(q -> prevContributorId.equals(q.getAssignedUserId()))
                    .count();
            if (remaining == 0) {
                closeContributorTask(assessmentId, prevContributorId,
                        "All questions reassigned to another contributor");
            }
        }

        // ── Create ActionItem for the new contributor ─────────────────────────────
        // One ActionItem per question — this is the contributor's trackable inbox item.
        // Does NOT include openWork=1. Section lock still applies — the contributor
        // sees read-only if the section was submitted before they answer.
        // openWork=1 is reserved exclusively for REVISION_REQUEST (formal re-answer bypass).
        //
        // navContext references the ActionItem's own ID so the fill page can resolve
        // isAssignmentEntry correctly (separate from isRevisionEntry).
        // We save twice: first to get the generated ID, then to write navContext with it.

        ActionItem item = ActionItem.builder()
                .tenantId(tenantId)
                .createdBy(assignerId)
                .assignedTo(contributorId)
                .sourceType(ActionItem.SourceType.SYSTEM)
                .sourceId(qi.getId())
                .entityType(ActionItem.EntityType.QUESTION_RESPONSE)
                .entityId(qi.getId())
                .title(qi.getQuestionTextSnapshot()
                        .substring(0, Math.min(80, qi.getQuestionTextSnapshot().length())))
                .status(ActionItem.Status.OPEN)
                .priority(ActionItem.Priority.MEDIUM)
                .remediationType("CONTRIBUTOR_ASSIGNMENT")
                .build();
        actionItemRepository.save(item); // generates item.getId()

        String navCtx = String.format(
                "{\"assigneeRoute\":\"/vendor/assessments/%d/fill" +
                        "?actionItemId=%d&questionInstanceId=%d\"" +
                        ",\"reviewerRoute\":\"/vendor/assessments/%d/fill\"" +
                        ",\"questionInstanceId\":%d,\"assessmentId\":%d}",
                assessmentId, item.getId(), qi.getId(),
                assessmentId, qi.getId(), assessmentId);
        item.setNavContext(navCtx);
        actionItemRepository.save(item);

        notificationService.send(contributorId, "QUESTION_ASSIGNED",
                "You have been assigned a question to answer",
                "QUESTION_RESPONSE", qi.getId());

        log.info("[CONTRIBUTOR] ActionItem created | itemId={} | contributorId={} | qi={} | assessmentId={}",
                item.getId(), contributorId, qi.getId(), assessmentId);
    }

    @GetMapping("/v1/assessments/{assessmentId}/my-sections")
    @Transactional(readOnly = true)
    @Operation(summary = "Responder fetches their assigned sections with questions")
    public ResponseEntity<ApiResponse<List<SectionInstanceResponse>>> getMySections(
            @PathVariable Long assessmentId) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        AssessmentTemplateInstance ti = templateInstanceRepository
                .findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateInstance for assessment", assessmentId));

        List<SectionInstanceResponse> sections =
                sectionInstanceRepository
                        .findByTemplateInstanceIdAndAssignedUserIdOrderBySectionOrderNo(ti.getId(), userId)
                        .stream()
                        .map(si -> {
                            List<QuestionInstanceResponse> questions =
                                    questionInstanceRepository
                                            .findBySectionInstanceIdOrderByOrderNo(si.getId())
                                            .stream()
                                            .map(qi -> {
                                                var responseOpt = responseRepository
                                                        .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, qi.getId());

                                                List<OptionInstanceResponse> options =
                                                        optionInstanceRepository
                                                                .findByQuestionInstanceIdOrderByOrderNo(qi.getId())
                                                                .stream()
                                                                .map(o -> OptionInstanceResponse.builder()
                                                                        .optionInstanceId(o.getId())
                                                                        .optionValue(o.getOptionValue())
                                                                        .score(o.getScore())
                                                                        .build())
                                                                .toList();

                                                AnswerResponse answer = responseOpt.map(r -> {
                                                    java.util.List<Long> multiIds = null;
                                                    if (r.getResponseText() != null && r.getResponseText().startsWith("[")) {
                                                        try {
                                                            Long[] arr = new com.fasterxml.jackson.databind.ObjectMapper()
                                                                    .readValue(r.getResponseText(), Long[].class);
                                                            multiIds = java.util.Arrays.asList(arr);
                                                        } catch (Exception ignored) {}
                                                    }
                                                    String answeredByName = null;
                                                    if (r.getSubmittedBy() != null) {
                                                        answeredByName = userRepository.findById(r.getSubmittedBy())
                                                                .map(u -> {
                                                                    String fn = u.getFirstName() != null ? u.getFirstName() : "";
                                                                    String ln = u.getLastName()  != null ? u.getLastName()  : "";
                                                                    String full = (fn + " " + ln).trim();
                                                                    return full.isEmpty() ? u.getEmail() : full;
                                                                }).orElse(null);
                                                    }
                                                    return AnswerResponse.builder()
                                                            .responseId(r.getId())
                                                            .responseText(multiIds != null ? null : r.getResponseText())
                                                            .selectedOptionInstanceId(r.getSelectedOptionInstanceId())
                                                            .selectedOptionInstanceIds(multiIds)
                                                            .scoreEarned(r.getScoreEarned())
                                                            .reviewerStatus(r.getReviewerStatus())
                                                            .submittedAt(r.getSubmittedAt())
                                                            .answeredBy(r.getSubmittedBy())
                                                            .answeredByName(answeredByName)
                                                            .build();
                                                }).orElse(null);

                                                String assignedName = null;
                                                if (qi.getAssignedUserId() != null) {
                                                    assignedName = userRepository.findById(qi.getAssignedUserId())
                                                            .map(u -> {
                                                                String fn = u.getFirstName() != null ? u.getFirstName() : "";
                                                                String ln = u.getLastName()  != null ? u.getLastName()  : "";
                                                                String full = (fn + " " + ln).trim();
                                                                return full.isEmpty() ? u.getEmail() : full;
                                                            }).orElse(null);
                                                }
                                                // Resolve review assistant name separately —
                                                // reviewerAssignedUserId is the org-side assignment (step 9)
                                                String reviewerAssignedName = null;
                                                if (qi.getReviewerAssignedUserId() != null) {
                                                    reviewerAssignedName = userRepository.findById(qi.getReviewerAssignedUserId())
                                                            .map(u -> {
                                                                String fn = u.getFirstName() != null ? u.getFirstName() : "";
                                                                String ln = u.getLastName()  != null ? u.getLastName()  : "";
                                                                String full = (fn + " " + ln).trim();
                                                                return full.isEmpty() ? u.getEmail() : full;
                                                            }).orElse(null);
                                                }
                                                return QuestionInstanceResponse.builder()
                                                        .questionInstanceId(qi.getId())
                                                        .questionText(qi.getQuestionTextSnapshot())
                                                        .responseType(qi.getResponseType())
                                                        .weight(qi.getWeight())
                                                        .mandatory(qi.isMandatory())
                                                        .orderNo(qi.getOrderNo())
                                                        .options(options)
                                                        .currentResponse(answer)
                                                        .assignedUserId(qi.getAssignedUserId())
                                                        .assignedUserName(assignedName)
                                                        .reviewerAssignedUserId(qi.getReviewerAssignedUserId())
                                                        .reviewerAssignedUserName(reviewerAssignedName)
                                                        .build();
                                            })
                                            .toList();

                            String submittedByName = null;
                            if (si.getSubmittedBy() != null) {
                                submittedByName = userRepository.findById(si.getSubmittedBy())
                                        .map(u -> {
                                            String fn = u.getFirstName() != null ? u.getFirstName() : "";
                                            String ln = u.getLastName()  != null ? u.getLastName()  : "";
                                            String full = (fn + " " + ln).trim();
                                            return full.isEmpty() ? u.getEmail() : full;
                                        }).orElse(null);
                            }
                            return SectionInstanceResponse.builder()
                                    .sectionInstanceId(si.getId())
                                    .sectionName(si.getSectionNameSnapshot())
                                    .sectionOrderNo(si.getSectionOrderNo())
                                    .assignedUserId(si.getAssignedUserId())
                                    .submittedAt(si.getSubmittedAt())
                                    .submittedBy(si.getSubmittedBy())
                                    .submittedByName(submittedByName)
                                    .reopenedAt(si.getReopenedAt())
                                    .questions(questions)
                                    .build();
                        })
                        .toList();

        return ResponseEntity.ok(ApiResponse.success(sections));
    }

    @GetMapping("/v1/assessments/{assessmentId}/my-questions")
    @Transactional(readOnly = true)
    @Operation(summary = "Contributor fetches their assigned questions")
    public ResponseEntity<ApiResponse<List<QuestionInstanceResponse>>> getMyQuestions(
            @PathVariable Long assessmentId) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        List<QuestionInstanceResponse> questions =
                questionInstanceRepository.findByAssessmentIdAndAssignedUserIdOrderByOrderNo(assessmentId, userId)
                        .stream()
                        .map(qi -> {
                            var responseOpt = responseRepository
                                    .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, qi.getId());
                            AnswerResponse answer = responseOpt.map(r -> {
                                java.util.List<Long> multiIds = null;
                                if (r.getResponseText() != null && r.getResponseText().startsWith("[")) {
                                    try {
                                        Long[] arr = new com.fasterxml.jackson.databind.ObjectMapper()
                                                .readValue(r.getResponseText(), Long[].class);
                                        multiIds = java.util.Arrays.asList(arr);
                                    } catch (Exception ignored) {}
                                }
                                return AnswerResponse.builder()
                                        .responseId(r.getId())
                                        .responseText(multiIds != null ? null : r.getResponseText())
                                        .selectedOptionInstanceId(r.getSelectedOptionInstanceId())
                                        .selectedOptionInstanceIds(multiIds)
                                        .scoreEarned(r.getScoreEarned())
                                        .submittedAt(r.getSubmittedAt())
                                        .build();
                            }).orElse(null);
                            var sectionInst = qi.getSectionInstanceId() != null
                                    ? sectionInstanceRepository.findById(qi.getSectionInstanceId()).orElse(null)
                                    : null;
                            String sectionName = sectionInst != null
                                    ? sectionInst.getSectionNameSnapshot() : null;
                            java.time.LocalDateTime sectionSubmittedAt = sectionInst != null
                                    ? sectionInst.getSubmittedAt() : null;
                            return QuestionInstanceResponse.builder()
                                    .questionInstanceId(qi.getId())
                                    .questionText(qi.getQuestionTextSnapshot())
                                    .responseType(qi.getResponseType())
                                    .weight(qi.getWeight())
                                    .mandatory(qi.isMandatory())
                                    .orderNo(qi.getOrderNo())
                                    .assignedUserId(qi.getAssignedUserId())
                                    .sectionInstanceId(qi.getSectionInstanceId())
                                    .sectionName(sectionName)
                                    .sectionSubmittedAt(sectionSubmittedAt)
                                    .currentResponse(answer)
                                    .options(optionInstanceRepository
                                            .findByQuestionInstanceIdOrderByOrderNo(qi.getId())
                                            .stream()
                                            .map(o -> OptionInstanceResponse.builder()
                                                    .optionInstanceId(o.getId())
                                                    .optionValue(o.getOptionValue())
                                                    .score(o.getScore())
                                                    .build())
                                            .toList())
                                    .build();
                        })
                        .toList();

        return ResponseEntity.ok(ApiResponse.success(questions));
    }

    // ══════════════════════════════════════════════════════════════
    // STEP-GATED ACCESS GUARD — private helper
    // ══════════════════════════════════════════════════════════════

    /**
     * Closes a contributor's sub-task on the active FILL step when they have no
     * remaining questions. Used by unassign and reassign flows.
     * Sets TaskStatus.REJECTED directly — bypasses performAction to avoid
     * step-level rejection logic (we only want to remove this sub-task from inbox).
     */
    private void closeContributorTask(Long assessmentId, Long contributorId, String reason) {
        VendorAssessment va = assessmentRepository.findById(assessmentId).orElse(null);
        if (va == null) return;
        VendorAssessmentCycle cycle = cycleRepository.findById(va.getCycleId()).orElse(null);
        if (cycle == null || cycle.getWorkflowInstanceId() == null) return;

        stepInstanceRepository
                .findByWorkflowInstanceIdOrderByCreatedAtAsc(cycle.getWorkflowInstanceId())
                .stream()
                .filter(si -> si.getSnapStepAction() == com.kashi.grc.workflow.enums.StepAction.FILL
                        && si.getStatus() == com.kashi.grc.workflow.enums.StepStatus.IN_PROGRESS)
                .findFirst()
                .ifPresent(fillStep ->
                        taskInstanceRepository.findByStepInstanceId(fillStep.getId())
                                .stream()
                                .filter(t -> contributorId.equals(t.getAssignedUserId())
                                        && (t.getStatus() == TaskStatus.PENDING
                                        || t.getStatus() == TaskStatus.IN_PROGRESS))
                                .forEach(t -> {
                                    t.setStatus(TaskStatus.REJECTED);
                                    t.setActedAt(java.time.LocalDateTime.now());
                                    t.setRemarks(reason);
                                    taskInstanceRepository.save(t);
                                    log.info("[CONTRIBUTOR-TASK] Closed | contributorId={} | taskId={} | reason={}",
                                            contributorId, t.getId(), reason);
                                })
                );
    }

    /**
     * Asserts that the current user has an active (PENDING or IN_PROGRESS) task
     * associated with the workflow instance that drives this assessment.
     */
    private void assertUserHasActiveTask(VendorAssessment assessment, Long userId, String action) {
        User currentUser = utilityService.getLoggedInDataContext();
        boolean isSystemUser = currentUser.getRoles().stream()
                .anyMatch(r -> r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.SYSTEM);
        if (isSystemUser) {
            log.debug("[ASSESSMENT-GUARD] System user — skipping task guard | userId={} | assessmentId={}",
                    userId, assessment.getId());
            return;
        }

        VendorAssessmentCycle cycle = cycleRepository.findById(assessment.getCycleId()).orElse(null);
        if (cycle == null || cycle.getWorkflowInstanceId() == null) {
            log.debug("[ASSESSMENT-GUARD] No workflow instance linked for assessmentId={} — skipping task guard",
                    assessment.getId());
            return;
        }

        Long workflowInstanceId = cycle.getWorkflowInstanceId();

        List<TaskInstance> activeTasks = new ArrayList<>();
        activeTasks.addAll(taskInstanceRepository.findByAssignedUserIdAndStatus(userId, TaskStatus.PENDING));
        activeTasks.addAll(taskInstanceRepository.findByAssignedUserIdAndStatus(userId, TaskStatus.IN_PROGRESS));

        boolean hasActiveTask = activeTasks.stream().anyMatch(task -> {
            StepInstance si = stepInstanceRepository.findById(task.getStepInstanceId()).orElse(null);
            return si != null && workflowInstanceId.equals(si.getWorkflowInstanceId());
        });

        if (!hasActiveTask) {
            if (hasOpenActionItemForAssessment(userId, assessment.getId(), assessment.getTenantId())) {
                log.info("[ASSESSMENT-GUARD] Open obligation bypass | userId={} | assessmentId={} | action='{}'",
                        userId, assessment.getId(), action);
                return;
            }

            log.warn("[ASSESSMENT-GUARD] Access denied | userId={} | assessmentId={} | " +
                            "workflowInstanceId={} | action='{}' — no active task or open obligation",
                    userId, assessment.getId(), workflowInstanceId, action);
            throw new BusinessException("ACCESS_DENIED",
                    "You do not have an active task or open obligation for this assessment.",
                    HttpStatus.FORBIDDEN);
        }

        log.debug("[ASSESSMENT-GUARD] Access granted | userId={} | assessmentId={} | action='{}'",
                userId, assessment.getId(), action);
    }

    /**
     * Checks if userId has any open action item that relates to this assessment.
     */
    private boolean hasOpenActionItemForAssessment(Long userId, Long assessmentId, Long tenantId) {
        boolean directMatch = !actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedTo(userId))
                        .and(ActionItemSpecification.forEntity(
                                ActionItem.EntityType.ASSESSMENT, assessmentId))
                        .and(ActionItemSpecification.open())
        ).isEmpty();

        if (directMatch) return true;

        return actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedTo(userId))
                        .and(ActionItemSpecification.withEntityType(ActionItem.EntityType.QUESTION_RESPONSE))
                        .and(ActionItemSpecification.open())
        ).stream().anyMatch(ai ->
                questionInstanceRepository.findById(ai.getEntityId())
                        .map(qi -> assessmentId.equals(qi.getAssessmentId()))
                        .orElse(false)
        );
    }

    /**
     * Read-only access guard — allows any user who has EVER had a task
     * on this workflow instance, regardless of task status.
     */
    private void assertUserHasParticipated(VendorAssessment assessment, Long userId, String action) {
        User currentUser = utilityService.getLoggedInDataContext();
        boolean isSystemUser = currentUser.getRoles().stream()
                .anyMatch(r -> r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.SYSTEM);
        if (isSystemUser) return;

        boolean isOrgUser = currentUser.getRoles().stream()
                .anyMatch(r -> r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.ORGANIZATION);
        if (isOrgUser) return;

        VendorAssessmentCycle cycle = cycleRepository.findById(assessment.getCycleId()).orElse(null);
        if (cycle == null || cycle.getWorkflowInstanceId() == null) return;

        Long workflowInstanceId = cycle.getWorkflowInstanceId();

        List<TaskInstance> allUserTasks = taskInstanceRepository.findByAssignedUserId(userId);
        boolean hasParticipated = allUserTasks.stream().anyMatch(task -> {
            StepInstance si = stepInstanceRepository.findById(task.getStepInstanceId()).orElse(null);
            return si != null && workflowInstanceId.equals(si.getWorkflowInstanceId());
        });

        if (!hasParticipated) {
            log.warn("[ASSESSMENT-GUARD] Read access denied | userId={} | assessmentId={} | action='{}'",
                    userId, assessment.getId(), action);
            throw new BusinessException("ACCESS_DENIED",
                    "You do not have access to this assessment.",
                    HttpStatus.FORBIDDEN);
        }

        log.debug("[ASSESSMENT-GUARD] Read access granted | userId={} | assessmentId={}",
                userId, assessment.getId());
    }
}