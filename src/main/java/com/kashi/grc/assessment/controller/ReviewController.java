package com.kashi.grc.assessment.controller;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.domain.DocumentLink;
import com.kashi.grc.document.repository.DocumentLinkRepository;
import com.kashi.grc.document.repository.DocumentRepository;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.enums.*;
import com.kashi.grc.workflow.event.TaskSectionEvent;
import com.kashi.grc.workflow.repository.*;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * ReviewController — org-side review parity + remediation endpoints.
 *
 * ANALOGY (full):
 *   VENDOR_CISO        ≅  ORG_CISO          (orchestrator, assigns sections)
 *   VENDOR_RESPONDER   ≅  ORG_REVIEWER      (section owner, assigns to sub-role, submits section)
 *   VENDOR_CONTRIBUTOR ≅  ORG_REVIEW_ASST   (question evaluator, submits group, sub-task in inbox)
 *
 * REPORT VERSIONING:
 *   Reports are non-blocking. v1 is issued at workflow completion regardless of open items.
 *   Each remediation closure decrements open_remediation_count. When it hits 0, v-next is
 *   auto-generated. Reviewer can also manually trigger re-generation at any time.
 *
 * COMPOUND TASK SECTIONS (step 9 — Reviewers Evaluate Assigned Questions):
 *   SCORE_ANSWERS        (required) — auto-completed when reviewer submits all their sections.
 *   SECTION_REVIEW_COMPLETE (required, renamed from ADD_REVIEW_COMMENTS) — auto-completed same.
 *   FLAG_ISSUES          (optional) — not required, auto-completed same.
 *
 *   When all reviewer sections are submitted, SECTION_REVIEW_COMPLETE event fires.
 *   SCORE_ANSWERS fires automatically from saveReviewerEval when all questions evaluated.
 *   Both events together satisfy the compound task gate → auto-approves the reviewer task.
 */
@Slf4j
@RestController
@Tag(name = "Org Review — Parity & Remediation")
@RequiredArgsConstructor
@RequestMapping("/v1/assessments")
public class ReviewController {

    private final VendorAssessmentRepository           assessmentRepository;
    private final VendorAssessmentCycleRepository      cycleRepository;
    private final AssessmentTemplateInstanceRepository templateInstanceRepository;
    private final AssessmentSectionInstanceRepository  sectionInstanceRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final AssessmentResponseRepository         responseRepository;
    private final AssessmentOptionInstanceRepository   optionInstanceRepository;
    private final ReviewerAssistantSectionSubmissionRepository assistantSubmissionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentLinkRepository documentLinkRepository;
    private final StorageService storageService;
    private final ActionItemRepository                 actionItemRepository;
    private final StepInstanceRepository               stepInstanceRepository;
    private final TaskInstanceRepository               taskInstanceRepository;
    private final WorkflowEngineService                workflowEngineService;
    private final TaskSectionCompletionService         sectionCompletionService;
    private final UserRepository                       userRepository;
    private final NotificationService                  notificationService;
    private final UtilityService                       utilityService;
    private final ApplicationEventPublisher            eventPublisher;

    // ══════════════════════════════════════════════════════════════════════
    // 1. REVIEWER SECTION MANAGEMENT  (mirrors Responder patterns)
    // ══════════════════════════════════════════════════════════════════════

    @PutMapping("/{assessmentId}/sections/{sectionInstanceId}/reviewer-assign")
    @Transactional
    @Operation(summary = "Org CISO assigns section to a reviewer (writes reviewerAssignedUserId, never assignedUserId)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewerAssignSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId,
            @RequestBody Map<String, Long> body) {

        Long reviewerId = body.get("userId");
        if (reviewerId == null)
            throw new com.kashi.grc.common.exception.ValidationException("userId is required");

        AssessmentTemplateInstance ti = templateInstanceRepository.findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateInstance", assessmentId));
        AssessmentSectionInstance si = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SectionInstance", sectionInstanceId));

        if (!si.getTemplateInstanceId().equals(ti.getId()))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "SectionInstance does not belong to assessment " + assessmentId);

        si.setReviewerAssignedUserId(reviewerId);
        sectionInstanceRepository.save(si);

        log.info("[REVIEWER-ASSIGN-SECTION] si={} → reviewerId={} | assessmentId={}", sectionInstanceId, reviewerId, assessmentId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId", assessmentId, "sectionInstanceId", sectionInstanceId,
                "reviewerAssignedUserId", reviewerId,
                "reviewerAssignedUserName", resolveUserName(reviewerId))));
    }

    @GetMapping("/{assessmentId}/my-reviewer-sections")
    @Transactional(readOnly = true)
    @Operation(summary = "Reviewer fetches their assigned sections with questions (filtered by reviewerAssignedUserId). " +
            "Falls back to ALL sections when no explicit assignment exists but caller has an active EVALUATE task.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyReviewerSections(
            @PathVariable Long assessmentId,
            @RequestParam(required = false) Long taskId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        AssessmentTemplateInstance ti = templateInstanceRepository.findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateInstance", assessmentId));

        // Primary: sections explicitly assigned to this reviewer via reviewer-assign endpoint.
        List<AssessmentSectionInstance> assignedSections =
                sectionInstanceRepository
                        .findByTemplateInstanceIdAndReviewerAssignedUserIdOrderBySectionOrderNo(ti.getId(), userId);

        // Fallback: if no explicit assignment exists, check whether the caller has an active
        // EVALUATE task for this assessment. If yes, return all sections.
        List<AssessmentSectionInstance> sectionList;
        if (!assignedSections.isEmpty()) {
            sectionList = assignedSections;
        } else {
            boolean hasActiveEvaluateTask = false;

            if (taskId != null) {
                // Fast path: taskId supplied from URL — direct lookup, no scanning.
                Optional<TaskInstance> maybeTask = taskInstanceRepository.findById(taskId);
                if (maybeTask.isPresent()) {
                    TaskInstance t = maybeTask.get();
                    log.debug("[MY-REVIEWER-SECTIONS] taskId={} assignedTo={} caller={} role={} status={}",
                            taskId, t.getAssignedUserId(), userId, t.getTaskRole(), t.getStatus());
                    if (userId.equals(t.getAssignedUserId())
                            && t.getTaskRole() == TaskRole.ACTOR
                            && (t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.IN_PROGRESS)) {
                        StepInstance si2 = stepInstanceRepository.findById(t.getStepInstanceId()).orElse(null);
                        if (si2 != null && si2.getSnapStepAction() == StepAction.EVALUATE) {
                            hasActiveEvaluateTask = cycleRepository
                                    .findByWorkflowInstanceId(si2.getWorkflowInstanceId())
                                    .map(cycle -> assessmentRepository.findByCycleId(cycle.getId())
                                            .stream().anyMatch(a -> assessmentId.equals(a.getId())))
                                    .orElse(false);
                        }
                    }
                }
            }
            if (!hasActiveEvaluateTask) {
                hasActiveEvaluateTask = taskInstanceRepository.findByAssignedUserId(userId)
                        .stream()
                        .filter(t -> t.getTaskRole() == TaskRole.ACTOR
                                && (t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.IN_PROGRESS))
                        .anyMatch(t -> {
                            StepInstance si2 = stepInstanceRepository.findById(t.getStepInstanceId()).orElse(null);
                            if (si2 == null || si2.getSnapStepAction() != StepAction.EVALUATE) return false;
                            return cycleRepository.findByWorkflowInstanceId(si2.getWorkflowInstanceId())
                                    .map(cycle -> assessmentRepository.findByCycleId(cycle.getId())
                                            .stream().anyMatch(a -> assessmentId.equals(a.getId())))
                                    .orElse(false);
                        });
            }
            log.info("[MY-REVIEWER-SECTIONS] assessmentId={} userId={} taskId={} hasActiveEvaluateTask={}",
                    assessmentId, userId, taskId, hasActiveEvaluateTask);
            sectionList = hasActiveEvaluateTask
                    ? sectionInstanceRepository.findByTemplateInstanceIdOrderBySectionOrderNo(ti.getId())
                    : List.of();
        }

        List<Map<String, Object>> result =
                sectionList
                        .stream().map(si -> {
                            List<Map<String, Object>> qMaps =
                                    questionInstanceRepository.findBySectionInstanceIdOrderByOrderNo(si.getId())
                                            .stream().map(qi -> {
                                                var r = responseRepository.findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, qi.getId());
                                                Map<String, Object> m = new LinkedHashMap<>();
                                                m.put("questionInstanceId",      qi.getId());
                                                m.put("questionText",            qi.getQuestionTextSnapshot());
                                                m.put("responseType",            qi.getResponseType());
                                                m.put("weight",                  qi.getWeight());
                                                m.put("mandatory",               qi.isMandatory());
                                                m.put("orderNo",                 qi.getOrderNo());
                                                m.put("assignedUserId",          qi.getAssignedUserId());
                                                m.put("assignedUserName",        resolveUserName(qi.getAssignedUserId()));
                                                m.put("reviewerAssignedUserId",  qi.getReviewerAssignedUserId());
                                                m.put("reviewerAssignedUserName",resolveUserName(qi.getReviewerAssignedUserId()));
                                                // Options for SINGLE_CHOICE and MULTI_CHOICE questions
                                                List<Map<String, Object>> optMaps = optionInstanceRepository
                                                        .findByQuestionInstanceIdOrderByOrderNo(qi.getId())
                                                        .stream().map(opt -> {
                                                            Map<String, Object> om = new LinkedHashMap<>();
                                                            om.put("optionInstanceId", opt.getId());
                                                            om.put("optionValue",      opt.getOptionValue());
                                                            om.put("score",            opt.getScore());
                                                            om.put("orderNo",          opt.getOrderNo());
                                                            return om;
                                                        }).toList();
                                                m.put("options", optMaps);
                                                // Map.of() forbids null values — NPE when submittedBy is null (unanswered questions).
                                                // Use LinkedHashMap which accepts null values safely.
                                                m.put("currentResponse", r.map(resp -> {
                                                    Map<String, Object> rm = new LinkedHashMap<>();
                                                    rm.put("responseId",                resp.getId());
                                                    rm.put("responseText",              resp.getResponseText() != null ? resp.getResponseText() : "");
                                                    rm.put("scoreEarned",               resp.getScoreEarned() != null ? resp.getScoreEarned() : 0.0);
                                                    rm.put("reviewerStatus",            resp.getReviewerStatus() != null ? resp.getReviewerStatus() : "");
                                                    rm.put("submittedAt",               resp.getSubmittedAt() != null ? resp.getSubmittedAt().toString() : "");
                                                    rm.put("answeredByName",            resolveUserName(resp.getSubmittedBy()));
                                                    rm.put("selectedOptionInstanceId",  resp.getSelectedOptionInstanceId());
                                                    // Parse multi-choice IDs stored as JSON array in responseText e.g. "[4257,4259]"
                                                    List<Long> multiIds = new java.util.ArrayList<>();
                                                    String rt = resp.getResponseText();
                                                    if (rt != null && rt.startsWith("[")) {
                                                        try {
                                                            Long[] arr = new com.fasterxml.jackson.databind.ObjectMapper()
                                                                    .readValue(rt, Long[].class);
                                                            multiIds.addAll(java.util.Arrays.asList(arr));
                                                        } catch (Exception ignored) {}
                                                    }
                                                    rm.put("selectedOptionInstanceIds", multiIds);
                                                    return (Object) rm;
                                                }).orElse(null));
                                                return m;
                                            }).toList();
                            Map<String, Object> sMap = new LinkedHashMap<>();
                            sMap.put("sectionInstanceId",      si.getId());
                            sMap.put("sectionName",            si.getSectionNameSnapshot());
                            sMap.put("sectionOrderNo",         si.getSectionOrderNo());
                            sMap.put("reviewerAssignedUserId", si.getReviewerAssignedUserId());
                            sMap.put("reviewerSubmittedAt",    si.getReviewerSubmittedAt() != null ? si.getReviewerSubmittedAt().toString() : null);
                            sMap.put("reviewerSubmittedBy",    si.getReviewerSubmittedBy());
                            sMap.put("reviewerReopenedAt",     si.getReviewerReopenedAt() != null ? si.getReviewerReopenedAt().toString() : null);
                            sMap.put("questions", qMaps);
                            return sMap;
                        }).toList();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{assessmentId}/sections/{sectionInstanceId}/reviewer-submit")
    @Transactional
    @Operation(summary = "Reviewer submits section — stamps reviewerSubmittedAt, fires REVIEWER_SECTION_SUBMITTED gate when all done")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewerSubmitSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId,
            @RequestParam(required = false) Long taskId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        AssessmentSectionInstance si = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SectionInstance", sectionInstanceId));

        if (si.getReviewerSubmittedAt() != null)
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "sectionInstanceId", sectionInstanceId, "status", "ALREADY_SUBMITTED",
                    "submittedAt", si.getReviewerSubmittedAt().toString())));

        // Count open remediations for report snapshot — NOT blocking
        long openRemediations = countOpenRemediationItems(
                questionInstanceRepository.findBySectionInstanceIdOrderByOrderNo(sectionInstanceId)
                        .stream().map(AssessmentQuestionInstance::getId).toList(),
                assessmentId);

        si.setReviewerSubmittedAt(LocalDateTime.now());
        si.setReviewerSubmittedBy(userId);
        sectionInstanceRepository.save(si);

        log.info("[REVIEWER-SUBMIT] si={} | assessmentId={} | openRemediation={} | userId={}",
                sectionInstanceId, assessmentId, openRemediations, userId);

        // Check if all of this reviewer's assigned sections are now submitted
        if (taskId != null) {
            AssessmentTemplateInstance ti = templateInstanceRepository.findByAssessmentId(assessmentId).orElse(null);
            if (ti != null) {
                boolean allDone = sectionInstanceRepository
                        .findByTemplateInstanceIdAndReviewerAssignedUserIdOrderBySectionOrderNo(ti.getId(), userId)
                        .stream().allMatch(s -> s.getReviewerSubmittedAt() != null);

                if (allDone) {
                    // All reviewer sections submitted — fire SECTION_REVIEW_COMPLETE event.
                    // This marks the SECTION_REVIEW_COMPLETE compound task section done.
                    // Combined with SCORE_ANSWERS (auto-fired by saveReviewerEval when all
                    // questions are evaluated), this satisfies all required sections and
                    // triggers autoApproveTask → performAction(APPROVE) on the reviewer's task.
                    log.info("[REVIEWER-SUBMIT] All sections submitted — firing SECTION_REVIEW_COMPLETE | taskId={}", taskId);
                    try {
                        eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                                "SECTION_REVIEW_COMPLETE", taskId, userId,
                                "VENDOR_ASSESSMENT", assessmentId));
                    } catch (Exception e) {
                        log.warn("[REVIEWER-SUBMIT] SECTION_REVIEW_COMPLETE event failed | taskId={} | {}", taskId, e.getMessage());
                    }

                    // Fallback: if step has no sections configured, approve directly
                    if (!sectionCompletionService.hasSections(taskId)) {
                        log.warn("[REVIEWER-SUBMIT] No sections config — fallback APPROVE | taskId={}", taskId);
                        try {
                            var req = new com.kashi.grc.workflow.dto.request.TaskActionRequest();
                            req.setTaskInstanceId(taskId);
                            req.setActionType(ActionType.APPROVE);
                            req.setRemarks("All reviewer sections submitted");
                            workflowEngineService.performAction(req, userId);
                        } catch (BusinessException e) {
                            if (!"TASK_TERMINAL".equals(e.getErrorCode())) throw e;
                        }
                    }

                    log.info("[REVIEWER-SUBMIT] Gate fired | taskId={}", taskId);
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionInstanceId", sectionInstanceId, "status", "SUBMITTED",
                "submittedAt", si.getReviewerSubmittedAt().toString(),
                "openRemediations", openRemediations)));
    }

    @PostMapping("/{assessmentId}/sections/{sectionInstanceId}/reviewer-reopen")
    @Transactional
    @Operation(summary = "Org CISO reopens reviewer section (clears reviewerSubmittedAt)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewerReopenSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        User cu = utilityService.getLoggedInDataContext();
        if (cu.getRoles().stream().noneMatch(r -> r.getSide() == RoleSide.ORGANIZATION || r.getSide() == RoleSide.SYSTEM))
            throw new BusinessException("ACCESS_DENIED", "Only Org CISO/Admin can reopen.", HttpStatus.FORBIDDEN);

        AssessmentSectionInstance si = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SectionInstance", sectionInstanceId));
        if (si.getReviewerSubmittedAt() == null)
            return ResponseEntity.ok(ApiResponse.success(Map.of("sectionInstanceId", sectionInstanceId, "status", "NOT_SUBMITTED")));

        si.setReviewerSubmittedAt(null);
        si.setReviewerSubmittedBy(null);
        si.setReviewerReopenedAt(LocalDateTime.now());
        si.setReviewerReopenedBy(userId);
        sectionInstanceRepository.save(si);

        log.info("[REVIEWER-REOPEN] si={} | assessmentId={} | by={}", sectionInstanceId, assessmentId, userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionInstanceId", sectionInstanceId, "status", "REOPENED",
                "reopenedAt", si.getReviewerReopenedAt().toString())));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. REVIEW ASSISTANT SUB-TASKS  (mirrors Contributor patterns)
    // ══════════════════════════════════════════════════════════════════════

    @PutMapping("/{assessmentId}/questions/{questionInstanceId}/reviewer-assign-v2")
    @Transactional
    @Operation(summary = "Reviewer assigns question to assistant AND creates EVALUATE inbox sub-task")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewerAssignQuestionWithTask(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId,
            @RequestBody Map<String, Long> body) {

        Long assistantId = body.get("userId");
        if (assistantId == null)
            throw new com.kashi.grc.common.exception.ValidationException("userId is required");

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        if (!qi.getAssessmentId().equals(assessmentId))
            throw new com.kashi.grc.common.exception.ValidationException("QuestionInstance not in assessment " + assessmentId);

        if (assistantId.equals(qi.getReviewerAssignedUserId()))
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "assessmentId", assessmentId, "questionInstanceId", questionInstanceId,
                    "reviewerAssignedUserId", assistantId, "message", "Already assigned")));

        qi.setReviewerAssignedUserId(assistantId);
        questionInstanceRepository.save(qi);

        Long assignerId = utilityService.getLoggedInDataContext().getId();
        Long tenantId   = utilityService.getLoggedInDataContext().getTenantId();
        doCreateAssistantSubTask(assessmentId, assistantId, tenantId, assignerId);

        log.info("[REVIEWER-ASSIGN-Q-V2] qi={} → assistantId={} | assessmentId={}", questionInstanceId, assistantId, assessmentId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId", assessmentId, "questionInstanceId", questionInstanceId,
                "reviewerAssignedUserId", assistantId,
                "reviewerAssignedUserName", resolveUserName(assistantId),
                "message", "Assigned — assistant sub-task created")));
    }

    @PostMapping("/{assessmentId}/sections/{sectionInstanceId}/assistant-submit")
    @Transactional
    @Operation(summary = "Review assistant submits section eval group — mirrors contributorSubmitSection exactly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assistantSubmitSection(
            @PathVariable Long assessmentId,
            @PathVariable Long sectionInstanceId,
            @RequestParam(required = false) Long taskId) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        if (assistantSubmissionRepository.existsBySectionInstanceIdAndAssistantUserId(sectionInstanceId, userId))
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "status", "ALREADY_SUBMITTED", "sectionInstanceId", sectionInstanceId)));

        if (taskId != null) {
            TaskInstance task = taskInstanceRepository.findById(taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));
            if (!userId.equals(task.getAssignedUserId()))
                throw new BusinessException("ACCESS_DENIED", "Task does not belong to you.", HttpStatus.FORBIDDEN);
        }

        assistantSubmissionRepository.save(ReviewerAssistantSectionSubmission.builder()
                .assessmentId(assessmentId).sectionInstanceId(sectionInstanceId)
                .assistantUserId(userId).taskInstanceId(taskId)
                .submittedAt(LocalDateTime.now()).build());

        log.info("[ASSISTANT-SUBMIT] si={} | userId={} | taskId={}", sectionInstanceId, userId, taskId);

        if (taskId == null)
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "status", "SECTION_SUBMITTED", "sectionInstanceId", sectionInstanceId, "taskApproved", false)));

        long total     = assistantSubmissionRepository.countDistinctSectionsWithAssignments(assessmentId, userId);
        long submitted = assistantSubmissionRepository.countByTaskInstanceId(taskId);
        boolean allDone = submitted >= total && total > 0;

        if (allDone) {
            try {
                var req = new com.kashi.grc.workflow.dto.request.TaskActionRequest();
                req.setTaskInstanceId(taskId);
                req.setActionType(ActionType.APPROVE);
                req.setRemarks("All assigned section evaluations submitted — auto-approved");
                workflowEngineService.performAction(req, userId);
                log.info("[ASSISTANT-SUBMIT] Sub-task approved | taskId={}", taskId);
            } catch (BusinessException e) { if (!"TASK_TERMINAL".equals(e.getErrorCode())) throw e; }
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", allDone ? "TASK_APPROVED" : "SECTION_SUBMITTED",
                "sectionInstanceId", sectionInstanceId,
                "submittedSections", submitted, "totalSections", total, "taskApproved", allDone)));
    }

    @GetMapping("/{assessmentId}/assistant-section-status")
    @Transactional(readOnly = true)
    @Operation(summary = "Review assistant: which of their section groups are submitted")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAssistantSectionStatus(
            @PathVariable Long assessmentId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        return ResponseEntity.ok(ApiResponse.success(
                assistantSubmissionRepository.findByAssessmentIdAndAssistantUserId(assessmentId, userId)
                        .stream().map(s -> Map.<String, Object>of(
                                "sectionInstanceId", s.getSectionInstanceId(),
                                "submittedAt", s.getSubmittedAt().toString())).toList()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. CLARIFICATION & REMEDIATION
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/{assessmentId}/questions/{questionInstanceId}/request-clarification")
    @Transactional
    @Operation(summary = "Reviewer → assistant clarification (internal, vendor not notified).")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestClarification(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId,
            @RequestBody Map<String, String> body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        String description = body.getOrDefault("description", "").trim();
        if (description.isBlank())
            throw new com.kashi.grc.common.exception.ValidationException("description is required");

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        Long assistantId = qi.getReviewerAssignedUserId();
        if (assistantId == null)
            throw new BusinessException("NO_ASSISTANT_ASSIGNED",
                    "Assign a review assistant before requesting clarification.");

        ActionItem item = ActionItem.builder()
                .tenantId(tenantId).createdBy(userId).assignedTo(assistantId)
                .sourceType(ActionItem.SourceType.COMMENT).sourceId(questionInstanceId)
                .entityType(ActionItem.EntityType.QUESTION_RESPONSE).entityId(questionInstanceId)
                .title("Clarification requested: " + qi.getQuestionTextSnapshot().substring(0, Math.min(80, qi.getQuestionTextSnapshot().length())))
                .description(description).status(ActionItem.Status.OPEN).priority(ActionItem.Priority.MEDIUM)
                .resolutionReservedFor(userId)
                .navContext(String.format("{\"assigneeRoute\":\"/assessments/%d/review\",\"reviewerRoute\":\"/assessments/%d/review\",\"questionInstanceId\":%d}", assessmentId, assessmentId, questionInstanceId))
                .remediationType("CLARIFICATION").build();
        actionItemRepository.save(item);

        notificationService.send(assistantId, "REVIEW_CLARIFICATION_REQUESTED",
                resolveUserName(userId) + " requested clarification on a question",
                "QUESTION_RESPONSE", questionInstanceId);

        log.info("[CLARIFICATION] itemId={} | qi={} | assistant={}", item.getId(), questionInstanceId, assistantId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "actionItemId", item.getId(), "questionInstanceId", questionInstanceId,
                "assignedTo", assistantId, "remediationType", "CLARIFICATION", "status", "OPEN")));
    }

    @PostMapping("/{assessmentId}/questions/{questionInstanceId}/request-remediation")
    @Transactional
    @Operation(summary = "Reviewer → vendor remediation request (tracked issue, severity + due date, CISO notified)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestRemediation(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId,
            @RequestBody Map<String, String> body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        String severity         = body.getOrDefault("severity", "MEDIUM").toUpperCase();
        String description      = body.getOrDefault("description", "").trim();
        String expectedEvidence = body.getOrDefault("expectedEvidence", "");
        String dueDateStr       = body.get("dueDate");

        if (description.isBlank())
            throw new com.kashi.grc.common.exception.ValidationException("description is required");
        if (!Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(severity))
            throw new com.kashi.grc.common.exception.ValidationException("severity must be LOW/MEDIUM/HIGH/CRITICAL");

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        ActionItem.Priority priority = switch (severity) {
            case "CRITICAL" -> ActionItem.Priority.CRITICAL;
            case "HIGH"     -> ActionItem.Priority.HIGH;
            case "LOW"      -> ActionItem.Priority.LOW;
            default         -> ActionItem.Priority.MEDIUM;
        };

        LocalDateTime dueAt = null;
        if (dueDateStr != null && !dueDateStr.isBlank()) {
            try { dueAt = LocalDateTime.parse(dueDateStr); } catch (Exception ignored) {}
        }

        ActionItem item = ActionItem.builder()
                .tenantId(tenantId).createdBy(userId)
                .assignedTo(qi.getAssignedUserId())
                .assignedGroupRole("VENDOR_CISO")
                .sourceType(ActionItem.SourceType.COMMENT).sourceId(questionInstanceId)
                .entityType(ActionItem.EntityType.QUESTION_RESPONSE).entityId(questionInstanceId)
                .title("Remediation required: " + qi.getQuestionTextSnapshot().substring(0, Math.min(80, qi.getQuestionTextSnapshot().length())))
                .description(description).status(ActionItem.Status.OPEN).priority(priority).dueAt(dueAt)
                .resolutionReservedFor(userId)
                .navContext(String.format(
                        "{\"assigneeRoute\":\"/vendor/assessments/%d/responder-review\"," +
                                "\"reviewerRoute\":\"/assessments/%d/review\",\"questionInstanceId\":%d}",
                        assessmentId, assessmentId, questionInstanceId))
                .remediationType("REMEDIATION_REQUEST").severity(severity)
                .expectedEvidence(expectedEvidence.isBlank() ? null : expectedEvidence)
                .build();
        actionItemRepository.save(item);

        assessment.setOpenRemediationCount(
                (assessment.getOpenRemediationCount() != null ? assessment.getOpenRemediationCount() : 0) + 1);
        assessmentRepository.save(assessment);

        String msg = resolveUserName(userId) + " flagged a question for remediation [" + severity + "]";
        if (qi.getAssignedUserId() != null)
            notificationService.send(qi.getAssignedUserId(), "REMEDIATION_REQUESTED", msg, "QUESTION_RESPONSE", questionInstanceId);
        notifyCiso(assessment, msg, questionInstanceId);

        log.info("[REMEDIATION] itemId={} | qi={} | severity={} | dueAt={}", item.getId(), questionInstanceId, severity, dueAt);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "actionItemId", item.getId(), "questionInstanceId", questionInstanceId,
                "severity", severity, "remediationType", "REMEDIATION_REQUEST", "status", "OPEN",
                "assignedTo", qi.getAssignedUserId() != null ? qi.getAssignedUserId() : "VENDOR_CISO_GROUP",
                "dueAt", dueAt != null ? dueAt.toString() : "")));
    }

    @PostMapping("/{assessmentId}/action-items/{actionItemId}/accept-risk")
    @Transactional
    @Operation(summary = "Reviewer accepts risk on remediation item — closes it without vendor fix")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptRisk(
            @PathVariable Long assessmentId,
            @PathVariable Long actionItemId,
            @RequestBody Map<String, String> body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        String note   = body.getOrDefault("note", "Risk accepted by reviewer").trim();

        ActionItem item = actionItemRepository.findById(actionItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", actionItemId));
        if (!tenantId.equals(item.getTenantId()))
            throw new BusinessException("ACCESS_DENIED", "Not your tenant.", HttpStatus.FORBIDDEN);
        if (!"REMEDIATION_REQUEST".equals(item.getRemediationType()))
            throw new BusinessException("INVALID_OPERATION", "accept-risk only for REMEDIATION_REQUEST items.");

        item.setStatus(ActionItem.Status.RESOLVED);
        item.setAcceptedRisk(true);
        item.setAcceptedRiskBy(userId);
        item.setAcceptedRiskAt(LocalDateTime.now());
        item.setAcceptedRiskNote(note);
        item.setResolvedAt(LocalDateTime.now());
        item.setResolvedBy(userId);
        item.setResolutionNote("RISK_ACCEPTED: " + note);
        actionItemRepository.save(item);

        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        boolean reportTriggered = decrementAndMaybeReport(assessment, tenantId, userId);

        log.info("[ACCEPT-RISK] itemId={} | assessmentId={} | reportTriggered={}", actionItemId, assessmentId, reportTriggered);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "actionItemId", actionItemId, "status", "RESOLVED", "acceptedRisk", true,
                "note", note, "reportTriggered", reportTriggered,
                "openRemediations", assessment.getOpenRemediationCount() != null ? assessment.getOpenRemediationCount() : 0)));
    }

    @PostMapping("/{assessmentId}/action-items/{actionItemId}/validate-remediation")
    @Transactional
    @Operation(summary = "Reviewer validates vendor remediation — resolves item, triggers report v-next when last item")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateRemediation(
            @PathVariable Long assessmentId,
            @PathVariable Long actionItemId,
            @RequestBody(required = false) Map<String, String> body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        String note   = body != null ? body.getOrDefault("note", "Remediation validated") : "Remediation validated";

        ActionItem item = actionItemRepository.findById(actionItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", actionItemId));
        if (!tenantId.equals(item.getTenantId()))
            throw new BusinessException("ACCESS_DENIED", "Not your tenant.", HttpStatus.FORBIDDEN);
        if (!"REMEDIATION_REQUEST".equals(item.getRemediationType()))
            throw new BusinessException("INVALID_OPERATION", "validate-remediation only for REMEDIATION_REQUEST items.");

        item.setStatus(ActionItem.Status.RESOLVED);
        item.setResolvedAt(LocalDateTime.now());
        item.setResolvedBy(userId);
        item.setResolutionNote(note);
        actionItemRepository.save(item);

        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        boolean reportTriggered = decrementAndMaybeReport(assessment, tenantId, userId);

        log.info("[VALIDATE-REMEDIATION] itemId={} | assessmentId={} | reportTriggered={}", actionItemId, assessmentId, reportTriggered);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "actionItemId", actionItemId, "status", "RESOLVED",
                "openRemediations", assessment.getOpenRemediationCount() != null ? assessment.getOpenRemediationCount() : 0,
                "reportTriggered", reportTriggered)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. REPORT VERSIONING
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/{assessmentId}/generate-report")
    @Transactional
    @Operation(summary = "Manually generate/re-generate a versioned report (non-blocking)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateReport(
            @PathVariable Long assessmentId,
            @RequestBody(required = false) Map<String, String> body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        String remarks = body != null ? body.getOrDefault("remarks", "") : "";

        VendorAssessment assessment = assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        return ResponseEntity.ok(ApiResponse.success(
                generateReportInternal(assessment, userId, tenantId, "MANUAL", remarks)));
    }

    @GetMapping("/{assessmentId}/reports")
    @Transactional(readOnly = true)
    @Operation(summary = "List all report versions for an assessment")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getReports(
            @PathVariable Long assessmentId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        List<Map<String, Object>> reports = documentLinkRepository
                .findReportVersions("ASSESSMENT", assessmentId)
                .stream()
                .map(link -> {
                    Document doc = documentRepository.findById(link.getDocumentId()).orElse(null);
                    if (doc == null) return null;

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reportId",      doc.getId());
                    m.put("documentId",    doc.getId());
                    m.put("linkId",        link.getId());
                    m.put("reportVersion", doc.getVersion());
                    m.put("generatedAt",   doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
                    m.put("generatedByName", resolveUserName(doc.getUploadedBy()));
                    m.put("status",        doc.getStatus());

                    Map<?, ?> data = doc.getGeneratedData();
                    if (data != null) {
                        m.put("compliancePct",          data.get("compliancePct"));
                        m.put("totalEarnedScore",       data.get("totalEarnedScore"));
                        m.put("totalPossibleScore",     data.get("totalPossibleScore"));
                        m.put("riskRating",             data.get("riskRating"));
                        m.put("openRemediationCount",   data.get("openRemediationCount"));
                        m.put("openClarificationCount", data.get("openClarificationCount"));
                        m.put("triggerEvent",           data.get("triggerEvent"));
                        m.put("remarks",                data.get("remarks"));
                    }

                    if (doc.getS3Key() != null && "ACTIVE".equals(doc.getStatus())) {
                        try {
                            m.put("downloadUrl",
                                    storageService.generateDownloadUrl(doc.getS3Key(), false, doc.getFileName()));
                        } catch (Exception e) {
                            m.put("downloadUrl", null);
                        }
                    } else {
                        m.put("downloadUrl", null);
                    }

                    return m;
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return ResponseEntity.ok(ApiResponse.success(reports));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private Map<String, Object> generateReportInternal(
            VendorAssessment assessment, Long userId, Long tenantId,
            String triggerEvent, String remarks) {

        Long assessmentId = assessment.getId();

        double possible = questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessmentId)
                .stream().mapToDouble(q -> q.getWeight() != null ? q.getWeight() : 1.0).sum();
        double earned   = responseRepository.sumScoreByAssessmentId(assessmentId);
        double pct      = possible > 0 ? Math.round((earned / possible) * 10000.0) / 100.0 : 0.0;

        int openRemed = countOpenItemsByType(assessmentId, tenantId, "REMEDIATION_REQUEST");
        int openClar  = countOpenItemsByType(assessmentId, tenantId, "CLARIFICATION");

        int version = (int) documentLinkRepository
                .findReportVersions("ASSESSMENT", assessmentId)
                .stream()
                .filter(lnk -> {
                    Document d = documentRepository.findById(lnk.getDocumentId()).orElse(null);
                    return d != null && !"DELETED".equals(d.getStatus());
                })
                .count() + 1;

        Map<String, Object> generatedData = new java.util.HashMap<>();
        generatedData.put("reportVersion",          version);
        generatedData.put("compliancePct",          pct);
        generatedData.put("totalEarnedScore",       earned);
        generatedData.put("totalPossibleScore",     possible);
        generatedData.put("riskRating",             assessment.getRiskRating() != null ? assessment.getRiskRating() : "");
        generatedData.put("openRemediationCount",   openRemed);
        generatedData.put("openClarificationCount", openClar);
        generatedData.put("triggerEvent",           triggerEvent);
        generatedData.put("remarks",                remarks.isBlank() ? null : remarks);

        byte[] pdfBytes = new byte[0];
        String reportFilename = String.format(
                "vendor-assessment-report-v%d-assessment-%d.pdf", version, assessmentId);

        String s3Key = null;
        StorageService.ServerUploadResult uploadResult = null;
        if (pdfBytes.length > 0) {
            try {
                uploadResult = storageService.uploadSystemDocument(
                        tenantId, userId, pdfBytes, reportFilename, "application/pdf", "VENDOR_ASSESSMENT");
                s3Key = uploadResult.getS3Key();
            } catch (Exception e) {
                log.error("[REPORT] S3 upload failed: {}", e.getMessage(), e);
            }
        }

        Document reportDoc = Document.builder()
                .tenantId(tenantId)
                .uploadedBy(userId)
                .fileName(reportFilename)
                .title(String.format("Vendor Assessment Report v%d — Assessment #%d", version, assessmentId))
                .mimeType("application/pdf")
                .documentType("GENERATED_REPORT")
                .sourceModule("VENDOR_ASSESSMENT")
                .generatedData(generatedData)
                .s3Key(s3Key)
                .s3Bucket(s3Key != null ? storageService.getBucket() : null)
                .status(s3Key != null ? "ACTIVE" : "PENDING")
                .version(version)
                .contentLength(uploadResult != null ? uploadResult.getContentLength() : 0L)
                .checksumSha256(uploadResult != null ? uploadResult.getChecksumSha256() : null)
                .build();
        documentRepository.save(reportDoc);

        documentLinkRepository.save(DocumentLink.builder()
                .tenantId(tenantId)
                .documentId(reportDoc.getId())
                .entityType("ASSESSMENT")
                .entityId(assessmentId)
                .linkType("REPORT")
                .createdBy(userId)
                .createdAt(LocalDateTime.now())
                .notes(String.format("Trigger: %s", triggerEvent))
                .build());

        assessment.setOpenRemediationCount(openRemed);
        assessmentRepository.save(assessment);

        log.info("[REPORT] v{} | assessmentId={} | docId={} | {}% | openRemed={} | trigger={}",
                version, assessmentId, reportDoc.getId(), pct, openRemed, triggerEvent);

        return Map.of(
                "reportId",              reportDoc.getId(),
                "reportVersion",         version,
                "compliancePct",         pct,
                "totalEarnedScore",      earned,
                "totalPossibleScore",    possible,
                "riskRating",            assessment.getRiskRating() != null ? assessment.getRiskRating() : "",
                "openRemediationCount",  openRemed,
                "openClarificationCount",openClar,
                "triggerEvent",          triggerEvent
        );
    }

    private boolean decrementAndMaybeReport(VendorAssessment assessment, Long tenantId, Long userId) {
        int cur = assessment.getOpenRemediationCount() != null ? assessment.getOpenRemediationCount() : 0;
        int next = Math.max(0, cur - 1);
        assessment.setOpenRemediationCount(next);
        assessmentRepository.save(assessment);
        if (next == 0 && cur > 0) {
            generateReportInternal(assessment, userId, tenantId, "REMEDIATION_CLOSED",
                    "Auto-generated: all open remediation items resolved");
            return true;
        }
        return false;
    }

    private int countOpenItemsByType(Long assessmentId, Long tenantId, String type) {
        return (int) actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and((root, q, cb) -> cb.equal(root.get("remediationType"), type))
                        .and(ActionItemSpecification.open())
        ).stream().filter(ai ->
                questionInstanceRepository.findById(ai.getEntityId())
                        .map(qi -> assessmentId.equals(qi.getAssessmentId())).orElse(false)).count();
    }

    private long countOpenRemediationItems(List<Long> qiIds, Long assessmentId) {
        if (qiIds.isEmpty()) return 0;
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and((root, q, cb) -> cb.equal(root.get("remediationType"), "REMEDIATION_REQUEST"))
                        .and(ActionItemSpecification.open())
        ).stream().filter(ai -> qiIds.contains(ai.getEntityId())).count();
    }

    private void doCreateAssistantSubTask(Long assessmentId, Long assistantId, Long tenantId, Long assignerId) {
        VendorAssessment va = assessmentRepository.findById(assessmentId).orElse(null);
        if (va == null) return;
        cycleRepository.findById(va.getCycleId()).ifPresent(cycle -> {
            if (cycle.getWorkflowInstanceId() == null) return;
            stepInstanceRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(cycle.getWorkflowInstanceId())
                    .stream()
                    .filter(si -> si.getSnapStepAction() == StepAction.EVALUATE
                            && si.getStatus() == StepStatus.IN_PROGRESS)
                    .findFirst().ifPresent(evalStep -> {
                        boolean hasTask = taskInstanceRepository.findByStepInstanceId(evalStep.getId())
                                .stream().anyMatch(t -> assistantId.equals(t.getAssignedUserId())
                                        && (t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.IN_PROGRESS));
                        if (!hasTask) {
                            workflowEngineService.createSubTask(evalStep, assistantId, TaskRole.ACTOR, tenantId,
                                    "Review assistant assigned by reviewer " + assignerId);
                            log.info("[ASSISTANT-SUBTASK] Created | assistant={} | assessmentId={}", assistantId, assessmentId);
                        }
                    });
        });
    }

    private void notifyCiso(VendorAssessment assessment, String msg, Long qiId) {
        try {
            cycleRepository.findById(assessment.getCycleId()).ifPresent(cycle -> {
                if (cycle.getWorkflowInstanceId() == null) return;
                stepInstanceRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(cycle.getWorkflowInstanceId())
                        .stream().flatMap(si -> taskInstanceRepository.findByStepInstanceId(si.getId()).stream())
                        .filter(t -> "VENDOR_CISO".equals(t.getActorRoleName()))
                        .map(TaskInstance::getAssignedUserId).filter(Objects::nonNull).distinct()
                        .forEach(id -> notificationService.send(id, "REMEDIATION_REQUESTED", msg, "QUESTION_RESPONSE", qiId));
            });
        } catch (Exception e) { log.warn("[NOTIFY-CISO] {}", e.getMessage()); }
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(u -> {
            String full = ((u.getFirstName() != null ? u.getFirstName() : "") + " " +
                    (u.getLastName()  != null ? u.getLastName()  : "")).trim();
            return full.isEmpty() ? u.getEmail() : full;
        }).orElse(null);
    }
}