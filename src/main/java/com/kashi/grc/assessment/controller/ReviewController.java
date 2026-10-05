package com.kashi.grc.assessment.controller;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.event.ActionItemCreatedEvent;
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
    private final AssessmentReportRepository           assessmentReportRepository;
    private final AssessmentOptionInstanceRepository   optionInstanceRepository;
    private final ReviewerAssistantSectionSubmissionRepository assistantSubmissionRepository;
    // Shared with AssessmentController's contributor path — the loop that used
    // to be copied into both, plus the early-return fix neither of them had.
    private final com.kashi.grc.assessment.service.AssessmentObligationService obligationService;
    private final DocumentRepository documentRepository;
    private final DocumentLinkRepository documentLinkRepository;
    private final StorageService storageService;
    private final ActionItemRepository                 actionItemRepository;
    private final StepInstanceRepository               stepInstanceRepository;
    private final TaskInstanceRepository               taskInstanceRepository;
    private final WorkflowEngineService                workflowEngineService;
    private final TaskSectionCompletionService         sectionCompletionService;
    private final UserRepository                       userRepository;
    private final com.kashi.grc.usermanagement.service.user.UserDisplayNameService userDisplayNameService;
    private final NotificationService                  notificationService;
    private final UtilityService                       utilityService;
    /**
     * Publishes ActionItemCreatedEvent so a raised remediation can be escalated
     * to an Issue by AssessmentGuardFindingListener, the same way a KashiGuard
     * finding is.
     *
     * An event rather than a direct call to AssessmentIssueEscalationService.
     * Three reasons, in order of how much they would hurt:
     *
     *   • Escalating inline would create the Issue and START ITS WORKFLOW inside
     *     this request's transaction. A workflow that fails to start would then
     *     roll back the remediation itself — the reviewer's judgement lost
     *     because a blueprint was misconfigured.
     *   • The listener already runs AFTER_COMMIT, @Async, in REQUIRES_NEW, with
     *     a catch that leaves the finding standing if escalation fails. Every one
     *     of those properties would have to be rebuilt here.
     *   • Both raise paths — guard rule and reviewer — then reach the same
     *     escalation through the same door, so severity mapping, workflow
     *     resolution and the no-owner fallback cannot drift apart.
     */
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

    /**
     * Bulk-assign several sections to ONE reviewer in a single call.
     *
     *   PUT /v1/assessments/{assessmentId}/sections/reviewer-assign-batch
     *   Body: { "userId": 123, "sectionInstanceIds": [11, 12, 13] }
     *
     * Org-side mirror of AssessmentController#assignSectionsBatch. Writes
     * reviewerAssignedUserId only — never touches assignedUserId, which belongs
     * to the vendor-side responder.
     *
     * Validates the entire batch before writing so a bad id rolls back rather
     * than leaving a partial assignment. No route ambiguity with the single
     * endpoint: /sections/{id}/reviewer-assign vs /sections/reviewer-assign-batch.
     */
    @PutMapping("/{assessmentId}/sections/reviewer-assign-batch")
    @Transactional
    @Operation(summary = "Org CISO assigns multiple sections to one reviewer at once")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewerAssignSectionsBatch(
            @PathVariable Long assessmentId,
            @RequestBody Map<String, Object> body) {

        Long reviewerId = body.get("userId") instanceof Number n ? n.longValue() : null;
        if (reviewerId == null)
            throw new com.kashi.grc.common.exception.ValidationException("userId is required");

        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("sectionInstanceIds");
        if (rawIds == null || rawIds.isEmpty())
            throw new com.kashi.grc.common.exception.ValidationException("sectionInstanceIds is required");

        AssessmentTemplateInstance ti = templateInstanceRepository.findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateInstance", assessmentId));

        List<Long> sectionIds = rawIds.stream()
                .filter(Objects::nonNull)
                .map(Number::longValue)
                .distinct()
                .toList();

        List<AssessmentSectionInstance> targets = new ArrayList<>();
        for (Long sid : sectionIds) {
            AssessmentSectionInstance si = sectionInstanceRepository.findById(sid)
                    .orElseThrow(() -> new ResourceNotFoundException("SectionInstance", sid));
            if (!si.getTemplateInstanceId().equals(ti.getId()))
                throw new com.kashi.grc.common.exception.ValidationException(
                        "SectionInstance " + sid + " does not belong to assessment " + assessmentId);
            targets.add(si);
        }

        targets.forEach(si -> si.setReviewerAssignedUserId(reviewerId));
        sectionInstanceRepository.saveAll(targets);

        log.info("[REVIEWER-ASSIGN-SECTIONS-BATCH] {} section(s) -> reviewerId={} | assessmentId={}",
                targets.size(), reviewerId, assessmentId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("assessmentId",             assessmentId);
        resp.put("reviewerAssignedUserId",   reviewerId);
        resp.put("reviewerAssignedUserName", resolveUserName(reviewerId));
        resp.put("sectionInstanceIds",       sectionIds);
        resp.put("assigned",                 targets.size());
        resp.put("message",                  targets.size() + " section(s) assigned to reviewer");
        return ResponseEntity.ok(ApiResponse.success(resp));
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

        // ── Bulk-load everything upfront (was: 1 query per section for
        // questions, plus per-question queries for response, options, and up
        // to 3 user-name lookups each) ────────────────────────────────────

        Set<Long> sectionIds = sectionList.stream().map(AssessmentSectionInstance::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<AssessmentQuestionInstance> allQuestions = sectionIds.isEmpty()
                ? List.of()
                : questionInstanceRepository.findBySectionInstanceIdInOrderByOrderNo(sectionIds);
        Map<Long, List<AssessmentQuestionInstance>> questionsBySectionId = allQuestions.stream()
                .collect(java.util.stream.Collectors.groupingBy(AssessmentQuestionInstance::getSectionInstanceId));

        Map<Long, AssessmentResponse> latestResponseByQiId = responseRepository.findByAssessmentId(assessmentId)
                .stream().collect(java.util.stream.Collectors.toMap(
                        AssessmentResponse::getQuestionInstanceId, r -> r,
                        (a, b) -> a.getId() >= b.getId() ? a : b));

        List<Long> allQuestionIds = allQuestions.stream().map(AssessmentQuestionInstance::getId).toList();
        Map<Long, List<AssessmentOptionInstance>> optionsByQuestionId = allQuestionIds.isEmpty()
                ? Map.of()
                : optionInstanceRepository.findByQuestionInstanceIdInOrderByOrderNo(allQuestionIds).stream()
                  .collect(java.util.stream.Collectors.groupingBy(AssessmentOptionInstance::getQuestionInstanceId));

        Set<Long> userIdsToResolve = new java.util.HashSet<>();
        allQuestions.forEach(qi -> {
            if (qi.getAssignedUserId() != null) userIdsToResolve.add(qi.getAssignedUserId());
            if (qi.getReviewerAssignedUserId() != null) userIdsToResolve.add(qi.getReviewerAssignedUserId());
        });
        latestResponseByQiId.values().forEach(r -> {
            if (r.getSubmittedBy() != null) userIdsToResolve.add(r.getSubmittedBy());
        });
        // Section-level reviewer ids. The section map returned only the raw ids,
        // so the screen could say a section was submitted but not by whom — and
        // the names are already being resolved in the same batch, so this costs
        // nothing beyond two more entries in a set.
        sectionList.forEach(si -> {
            if (si.getReviewerAssignedUserId() != null) userIdsToResolve.add(si.getReviewerAssignedUserId());
            if (si.getReviewerSubmittedBy() != null)    userIdsToResolve.add(si.getReviewerSubmittedBy());
        });
        Map<Long, String> nameMap = userDisplayNameService.resolveNames(userIdsToResolve);

        List<Map<String, Object>> result =
                sectionList
                        .stream().map(si -> {
                            List<Map<String, Object>> qMaps =
                                    questionsBySectionId.getOrDefault(si.getId(), List.of())
                                            .stream().map(qi -> {
                                                var r = Optional.ofNullable(latestResponseByQiId.get(qi.getId()));
                                                Map<String, Object> m = new LinkedHashMap<>();
                                                m.put("questionInstanceId",      qi.getId());
                                                m.put("questionText",            qi.getQuestionTextSnapshot());
                                                m.put("responseType",            qi.getResponseType());
                                                m.put("weight",                  qi.getWeight());
                                                m.put("mandatory",               qi.isMandatory());
                                                m.put("orderNo",                 qi.getOrderNo());
                                                m.put("assignedUserId",          qi.getAssignedUserId());
                                                m.put("assignedUserName",        nameMap.get(qi.getAssignedUserId()));
                                                m.put("reviewerAssignedUserId",  qi.getReviewerAssignedUserId());
                                                m.put("reviewerAssignedUserName",nameMap.get(qi.getReviewerAssignedUserId()));
                                                // Options for SINGLE_CHOICE and MULTI_CHOICE questions
                                                List<Map<String, Object>> optMaps = optionsByQuestionId
                                                        .getOrDefault(qi.getId(), List.of())
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
                                                    rm.put("answeredByName",            nameMap.get(resp.getSubmittedBy()));
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
                            sMap.put("reviewerAssignedUserName", nameMap.get(si.getReviewerAssignedUserId()));
                            sMap.put("reviewerSubmittedAt",    si.getReviewerSubmittedAt() != null ? si.getReviewerSubmittedAt().toString() : null);
                            sMap.put("reviewerSubmittedBy",    si.getReviewerSubmittedBy());
                            sMap.put("reviewerSubmittedByName", nameMap.get(si.getReviewerSubmittedBy()));
                            sMap.put("reviewerReopenedAt",     si.getReviewerReopenedAt() != null ? si.getReviewerReopenedAt().toString() : null);
                            sMap.put("questions", qMaps);
                            return sMap;
                        }).toList();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * GET /v1/assessments/{assessmentId}/review-summary
     *
     * WHO REVIEWED WHAT, across every reviewer — not just the caller.
     *
     * my-reviewer-sections above answers a different question: "which sections
     * am *I* working on". It filters on reviewerAssignedUserId = me and, for a
     * caller with no assignment and no active EVALUATE task, returns an empty
     * list. That is correct for a reviewer at work and useless for the CISO or
     * the org admin who wants to see the state of the review as a whole: they
     * get a blank Review tab and no way to tell an unreviewed assessment from
     * one where everybody has finished.
     *
     * This is a separate, read-only endpoint rather than a flag on the other
     * one, because the two have different shapes, different audiences and
     * different failure modes, and because widening my-reviewer-sections would
     * change what an in-flight reviewer sees mid-assessment.
     *
     * It returns one row per section with:
     *   - who the section is assigned to, and who submitted it, by NAME
     *   - the verdict distribution: pass / partial / fail / pending, and the
     *     count of questions with no answer at all (which score as FAIL)
     *   - per question: the verdict, who answered, and the reviewer of record
     *
     * It deliberately does NOT return answer text, comments or evidence. This
     * is a progress view, and the three comment channels have their own
     * visibility rules that this endpoint has no business reimplementing.
     * Anyone who needs to read an answer opens the question.
     *
     * Verdict vocabulary is PASS / PARTIAL / FAIL — the values saveReviewerEval
     * validates and the values AssessmentResponseRepositoryImpl scores against
     * (FAIL → 0, PARTIAL → half weight). "PENDING" and empty both mean not yet
     * evaluated; they are counted together.
     */
    @GetMapping("/{assessmentId}/review-summary")
    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasAuthority('assessment.sections.view_all')")
    @Operation(summary = "Review progress across ALL reviewers — per-section verdict roll-up")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReviewSummary(
            @PathVariable Long assessmentId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        VendorAssessment assessment = assessmentRepository
                .findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        AssessmentTemplateInstance ti = templateInstanceRepository.findByAssessmentId(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("TemplateInstance", assessmentId));

        List<AssessmentSectionInstance> sections =
                sectionInstanceRepository.findByTemplateInstanceIdOrderBySectionOrderNo(ti.getId());

        Set<Long> sectionIds = sections.stream()
                .map(AssessmentSectionInstance::getId)
                .collect(java.util.stream.Collectors.toSet());

        List<AssessmentQuestionInstance> allQuestions = sectionIds.isEmpty()
                ? List.of()
                : questionInstanceRepository.findBySectionInstanceIdInOrderByOrderNo(sectionIds);
        Map<Long, List<AssessmentQuestionInstance>> questionsBySectionId = allQuestions.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AssessmentQuestionInstance::getSectionInstanceId));

        // One row per question instance — the newest response wins, same rule
        // as my-reviewer-sections uses.
        Map<Long, AssessmentResponse> latestByQiId = responseRepository.findByAssessmentId(assessmentId)
                .stream().collect(java.util.stream.Collectors.toMap(
                        AssessmentResponse::getQuestionInstanceId, r -> r,
                        (a, b) -> a.getId() >= b.getId() ? a : b));

        // Escalations. An item that carries a linkedIssueId has left this module
        // and is being worked in the external issue remediation workflow; the
        // Review tab shows that as a chip so a reviewer does not chase a finding
        // that somebody else already escalated.
        Map<Long, Long> linkedIssueByQiId = new HashMap<>();
        Map<Long, Long> openFindingsByQiId = new HashMap<>();
        if (!allQuestions.isEmpty()) {
            List<Long> qIds = allQuestions.stream().map(AssessmentQuestionInstance::getId).toList();
            actionItemRepository.findAll(
                            ActionItemSpecification.forTenant(tenantId)
                                    .and(ActionItemSpecification.forEntities(
                                            ActionItem.EntityType.QUESTION_RESPONSE, qIds)))
                    .forEach(ai -> {
                        if (ai.getRemediationType() == null) return;
                        if ("CONTRIBUTOR_ASSIGNMENT".equals(ai.getRemediationType())
                                || "REVIEWER_ASSIGNMENT".equals(ai.getRemediationType())) return;
                        if (ai.getLinkedIssueId() != null) {
                            linkedIssueByQiId.put(ai.getEntityId(), ai.getLinkedIssueId());
                        }
                        if (ai.getStatus() != ActionItem.Status.RESOLVED
                                && ai.getStatus() != ActionItem.Status.DISMISSED) {
                            openFindingsByQiId.merge(ai.getEntityId(), 1L, Long::sum);
                        }
                    });
        }

        Set<Long> userIds = new java.util.HashSet<>();
        sections.forEach(si -> {
            if (si.getReviewerAssignedUserId() != null) userIds.add(si.getReviewerAssignedUserId());
            if (si.getReviewerSubmittedBy() != null)    userIds.add(si.getReviewerSubmittedBy());
        });
        allQuestions.forEach(qi -> {
            if (qi.getReviewerAssignedUserId() != null) userIds.add(qi.getReviewerAssignedUserId());
        });
        latestByQiId.values().forEach(r -> {
            if (r.getSubmittedBy() != null) userIds.add(r.getSubmittedBy());
        });
        Map<Long, String> nameMap = userDisplayNameService.resolveNames(userIds);

        // Totals across the whole assessment, accumulated while walking sections
        // so the header does not need a second pass.
        int[] all = new int[5]; // pass, partial, fail, pending, unanswered

        List<Map<String, Object>> sectionMaps = new ArrayList<>();
        for (AssessmentSectionInstance si : sections) {
            List<AssessmentQuestionInstance> qs =
                    questionsBySectionId.getOrDefault(si.getId(), List.of());

            int pass = 0, partial = 0, fail = 0, pending = 0, unanswered = 0;
            List<Map<String, Object>> qMaps = new ArrayList<>();

            for (AssessmentQuestionInstance qi : qs) {
                AssessmentResponse r = latestByQiId.get(qi.getId());
                String verdict = (r == null || r.getReviewerStatus() == null
                        || r.getReviewerStatus().isBlank())
                        ? "PENDING" : r.getReviewerStatus();

                boolean answered = r != null
                        && ((r.getResponseText() != null && !r.getResponseText().isBlank())
                        || r.getSelectedOptionInstanceId() != null);
                if (!answered) unanswered++;

                switch (verdict) {
                    case "PASS"    -> pass++;
                    case "PARTIAL" -> partial++;
                    case "FAIL"    -> fail++;
                    default        -> pending++;
                }

                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("questionInstanceId",       qi.getId());
                qm.put("questionText",             qi.getQuestionTextSnapshot());
                qm.put("orderNo",                  qi.getOrderNo());
                qm.put("weight",                   qi.getWeight());
                qm.put("mandatory",                qi.isMandatory());
                qm.put("questionTag",              qi.getQuestionTagSnapshot());
                qm.put("reviewerStatus",           verdict);
                qm.put("answered",                 answered);
                qm.put("answeredByName",           r != null ? nameMap.get(r.getSubmittedBy()) : null);
                qm.put("reviewerAssignedUserId",   qi.getReviewerAssignedUserId());
                qm.put("reviewerAssignedUserName", nameMap.get(qi.getReviewerAssignedUserId()));
                qm.put("linkedIssueId",            linkedIssueByQiId.get(qi.getId()));
                qm.put("openFindingCount",         openFindingsByQiId.getOrDefault(qi.getId(), 0L));
                qMaps.add(qm);
            }

            all[0] += pass; all[1] += partial; all[2] += fail;
            all[3] += pending; all[4] += unanswered;

            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("sectionInstanceId",        si.getId());
            sm.put("sectionName",              si.getSectionNameSnapshot());
            sm.put("sectionOrderNo",           si.getSectionOrderNo());
            sm.put("reviewerAssignedUserId",   si.getReviewerAssignedUserId());
            sm.put("reviewerAssignedUserName", nameMap.get(si.getReviewerAssignedUserId()));
            sm.put("reviewerSubmittedAt",      si.getReviewerSubmittedAt() != null
                    ? si.getReviewerSubmittedAt().toString() : null);
            sm.put("reviewerSubmittedBy",      si.getReviewerSubmittedBy());
            sm.put("reviewerSubmittedByName",  nameMap.get(si.getReviewerSubmittedBy()));
            sm.put("reviewerReopenedAt",       si.getReviewerReopenedAt() != null
                    ? si.getReviewerReopenedAt().toString() : null);
            sm.put("totalQuestions",           qs.size());
            sm.put("evaluated",                qs.size() - pending);
            sm.put("passCount",                pass);
            sm.put("partialCount",             partial);
            sm.put("failCount",                fail);
            sm.put("pendingCount",             pending);
            sm.put("unansweredCount",          unanswered);
            sm.put("questions",                qMaps);
            sectionMaps.add(sm);
        }

        int totalQuestions = all[0] + all[1] + all[2] + all[3];

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assessmentId",     assessmentId);
        out.put("status",           assessment.getStatus());
        out.put("riskRating",       assessment.getRiskRating());
        out.put("totalQuestions",   totalQuestions);
        out.put("evaluated",        totalQuestions - all[3]);
        out.put("passCount",        all[0]);
        out.put("partialCount",     all[1]);
        out.put("failCount",        all[2]);
        out.put("pendingCount",     all[3]);
        out.put("unansweredCount",  all[4]);
        out.put("escalatedCount",   linkedIssueByQiId.size());
        out.put("sectionsTotal",    sections.size());
        out.put("sectionsSubmitted", sections.stream()
                .filter(s -> s.getReviewerSubmittedAt() != null).count());
        out.put("sections",         sectionMaps);

        return ResponseEntity.ok(ApiResponse.success(out));
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

        // ── THE SAME HOLE THE VENDOR-SIDE SUBMIT HAD ────────────────────────
        //
        // This endpoint had no guard at all: no task check, no ownership check.
        // Any authenticated user who knew an assessmentId and a
        // sectionInstanceId could stamp reviewerSubmittedAt on a section —
        // including one assigned to a different reviewer — which locks their
        // evaluations and can fire the step gate on their behalf.
        //
        // Two checks, both already idioms in this file:
        //
        //   the task is yours — the same four lines assistantSubmitSection
        //                       uses twenty lines below, which is why THAT
        //                       endpoint was never exposed this way
        //   the section is yours — reviewer_assigned_user_id is the org-side
        //                       owner, the mirror of assigned_user_id on the
        //                       vendor side
        //
        // An unassigned section stays open, exactly as on the vendor side: that
        // is the state before the org CISO has allocated anything, and the
        // endpoint's own fallback below already treats "no explicit section
        // assignments" as "all sections".
        if (taskId != null) {
            TaskInstance task = taskInstanceRepository.findById(taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));
            if (!userId.equals(task.getAssignedUserId()))
                throw new BusinessException("ACCESS_DENIED", "Task does not belong to you.", HttpStatus.FORBIDDEN);
        }
        if (si.getReviewerAssignedUserId() != null && !si.getReviewerAssignedUserId().equals(userId)) {
            throw new BusinessException("SECTION_NOT_YOURS",
                    "This section is assigned to another reviewer. Only its reviewer can submit it — "
                            + "reassign it first if it needs to move.",
                    HttpStatus.FORBIDDEN);
        }

        // Already stamped — but STILL evaluate the gate before returning, the
        // same correction submitSection received on the vendor side. Its
        // comment states the case: an admin task reset clears
        // task_section_items back to 0/N required while leaving the submission
        // stamps in place, and an early return that skips the gate leaves the
        // re-issued task impossible for anyone to complete — EVALUATE steps
        // have no task-level Submit button, and reopening is Org-CISO-only.
        //
        // markAllSectionsCompleteForTask is idempotent, and the condition is
        // the same one the normal path uses, so this only fires when every
        // section this reviewer owns really does carry reviewerSubmittedAt.
        if (si.getReviewerSubmittedAt() != null) {
            boolean reGateFired = fireReviewerGateIfAllSubmitted(assessmentId, userId, taskId);
            Map<String, Object> already = new LinkedHashMap<>();
            already.put("sectionInstanceId", sectionInstanceId);
            already.put("status", "ALREADY_SUBMITTED");
            already.put("submittedAt", si.getReviewerSubmittedAt().toString());
            already.put("gateFired", reGateFired);
            return ResponseEntity.ok(ApiResponse.success(already));
        }

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

        boolean gateFired = fireReviewerGateIfAllSubmitted(assessmentId, userId, taskId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionInstanceId", sectionInstanceId, "status", "SUBMITTED",
                "submittedAt", si.getReviewerSubmittedAt().toString(),
                "openRemediations", openRemediations,
                "gateFired", gateFired)));
    }

    /**
     * Fires the reviewer's compound-task gate when every section they are
     * responsible for carries reviewerSubmittedAt, and reports whether it did.
     *
     * Uses the same pattern as the vendor-side submitSection (Step 4): when all
     * sections are done, markAllSectionsCompleteForTask marks ALL snapshotted
     * compound-task sections complete atomically and auto-approves the task.
     * That avoids the fragile named-event approach (SCORE_ANSWERS +
     * SECTION_REVIEW_COMPLETE) which requires exact blueprint key matching and
     * is vulnerable to REQUIRES_NEW transaction rollbacks — and it is just as
     * well that it does, because neither ANSWERS_SCORED nor
     * SECTION_REVIEW_COMPLETE is fired by anything in this module.
     *
     * Extracted so the ALREADY_SUBMITTED path in reviewerSubmitSection can
     * evaluate it too. submitSection on the vendor side already carries the
     * comment explaining why that matters: an admin task reset clears
     * task_section_items back to 0/N while leaving the submission stamps in
     * place, and an early return that skips the gate leaves the re-issued task
     * impossible for anyone to complete.
     */
    private boolean fireReviewerGateIfAllSubmitted(Long assessmentId, Long userId, Long taskId) {
        if (taskId == null) return false;

        AssessmentTemplateInstance ti = templateInstanceRepository
                .findByAssessmentId(assessmentId).orElse(null);
        if (ti == null) return false;

        // Primary: sections explicitly assigned to this reviewer.
        List<AssessmentSectionInstance> reviewerSections =
                sectionInstanceRepository
                        .findByTemplateInstanceIdAndReviewerAssignedUserIdOrderBySectionOrderNo(
                                ti.getId(), userId);

        // Fallback: if no explicit assignment exists (Org CISO skipped per-section
        // assignment and reviewer is using the "active EVALUATE task" fallback path),
        // check ALL sections in the template. This mirrors getMyReviewerSections().
        // Without this, allDone is vacuously true on an empty stream, causing
        // SECTION_REVIEW_COMPLETE to fire prematurely on the first submit call.
        if (reviewerSections.isEmpty()) {
            log.info("[REVIEWER-SUBMIT] No explicit section assignments — falling back to all sections | taskId={} | userId={}", taskId, userId);
            reviewerSections = sectionInstanceRepository
                    .findByTemplateInstanceIdOrderBySectionOrderNo(ti.getId());
        }

        boolean allDone = !reviewerSections.isEmpty() &&
                reviewerSections.stream().allMatch(s -> s.getReviewerSubmittedAt() != null);

        log.info("[REVIEWER-SUBMIT] Gate check | taskId={} | sections={} | allDone={}",
                taskId, reviewerSections.size(), allDone);

        if (allDone) {
            log.info("[REVIEWER-SUBMIT] All sections submitted — calling markAllSectionsCompleteForTask | taskId={}", taskId);
            sectionCompletionService.markAllSectionsCompleteForTask(taskId, userId);
            log.info("[REVIEWER-SUBMIT] Task gate satisfied | taskId={}", taskId);
        }
        return allDone;
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

        // ── THE ASSISTANTS' OWN LOCKS HAVE TO COME OFF TOO ──────────────────
        //
        // Clearing reviewerSubmittedAt reopens the section for the REVIEWER.
        // A review assistant's lock is a different record — a
        // reviewer_assistant_section_submissions row per (section, assistant) —
        // and it was left in place, so an assistant whose section was reopened
        // could not re-lock it: assistantSubmitSection sees their row and
        // returns ALREADY_SUBMITTED.
        //
        // The vendor side already does this. contributor-reopen deletes the
        // matching contributor_section_submissions rows, for exactly this
        // reason, and this endpoint is supposed to be its mirror.
        //
        // Deleting the row is right HERE and wrong on re-assignment: a reopen
        // is somebody deliberately saying "this section is not finished", which
        // is precisely a statement that the lock was premature. A new
        // assignment says nothing about the work already locked.
        int assistantLocksCleared = 0;
        for (var sub : assistantSubmissionRepository.findBySectionInstanceId(sectionInstanceId)) {
            assistantSubmissionRepository.delete(sub);
            assistantLocksCleared++;
        }

        log.info("[REVIEWER-REOPEN] si={} | assessmentId={} | by={} | assistantLocksCleared={}",
                sectionInstanceId, assessmentId, userId, assistantLocksCleared);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionInstanceId", sectionInstanceId, "status", "REOPENED",
                "reopenedAt", si.getReviewerReopenedAt().toString(),
                "assistantLocksCleared", assistantLocksCleared)));
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

        // ── THE TASK IS YOURS — CHECKED BEFORE EITHER PATH ──────────────────
        //
        // Hoisted above the ALREADY_SUBMITTED branch, which it used to sit
        // under. That branch calls approveAssistantTaskIfAllSubmitted, whose
        // countByTaskInstanceId(taskId) is not scoped to the caller and is
        // returned in the response. The approve was never at risk —
        // WorkflowEngineService.performAction opens with requireTaskOwnership —
        // but the count was readable for somebody else's task.
        //
        // Mirrors the identical hoist in contributorSubmitSection.
        if (taskId != null) {
            TaskInstance task = taskInstanceRepository.findById(taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));
            if (!userId.equals(task.getAssignedUserId()))
                throw new BusinessException("ACCESS_DENIED", "Task does not belong to you.", HttpStatus.FORBIDDEN);
        }

        // ── ALREADY SUBMITTED, BUT STILL CLOSE THE OBLIGATIONS ──────────────
        //
        // The exact mirror of contributorSubmitSection's version of this, and
        // the same dead end: a review assistant given a SECOND question in a
        // section they have already locked can evaluate it — owedHere beats the
        // lock — and then had nothing that would close the obligation, because
        // the loop below sits past this return and the submission row from
        // their first pass is still there.
        //
        // Their REVIEWER_ASSIGNMENT item stayed OPEN forever and their sub-task
        // gate was never re-examined.
        if (assistantSubmissionRepository.existsBySectionInstanceIdAndAssistantUserId(sectionInstanceId, userId)) {

            int reclosed = obligationService.closeAssignmentObligations(
                    sectionInstanceId, userId,
                    utilityService.getLoggedInDataContext().getTenantId(),
                    com.kashi.grc.assessment.service.AssessmentObligationService.REVIEWER_ASSIGNMENT,
                    "Evaluations locked by review assistant");

            AssistantGate reGate = approveAssistantTaskIfAllSubmitted(assessmentId, userId, taskId);

            log.info("[ASSISTANT-SUBMIT] Re-submit on locked section | si={} | userId={} | " +
                            "obligationsClosed={} | taskApproved={}",
                    sectionInstanceId, userId, reclosed, reGate.approved());

            Map<String, Object> already = new LinkedHashMap<>();
            already.put("status", "ALREADY_SUBMITTED");
            already.put("sectionInstanceId", sectionInstanceId);
            already.put("obligationsClosed", reclosed);
            already.put("submittedSections", reGate.submitted());
            already.put("totalSections", reGate.total());
            already.put("taskApproved", reGate.approved());
            return ResponseEntity.ok(ApiResponse.success(already));
        }

        // Task ownership is checked above, in front of both paths.

        assistantSubmissionRepository.save(ReviewerAssistantSectionSubmission.builder()
                .assessmentId(assessmentId).sectionInstanceId(sectionInstanceId)
                .assistantUserId(userId).taskInstanceId(taskId)
                .submittedAt(LocalDateTime.now()).build());

        log.info("[ASSISTANT-SUBMIT] si={} | userId={} | taskId={}", sectionInstanceId, userId, taskId);

        // ── CLOSE THIS ASSISTANT'S OBLIGATIONS FOR THIS SECTION ────────────
        //
        // saveReviewerEval moves a REVIEWER_ASSIGNMENT to IN_PROGRESS when a
        // verdict is saved; this is where it closes, because this is where the
        // person says they are finished with the section.
        //
        // The loop that used to be written out here is now
        // AssessmentObligationService.closeAssignmentObligations, shared with
        // the contributor path it was copied from. Its javadoc carries the three
        // scopes; the one that matters most here is the third — a CLARIFICATION
        // raised on a question in this section is the reviewer asking the
        // assistant something, and it MUST survive the lock, because the open
        // obligation is what lets them back in to answer it.
        obligationService.closeAssignmentObligations(
                sectionInstanceId, userId,
                utilityService.getLoggedInDataContext().getTenantId(),
                com.kashi.grc.assessment.service.AssessmentObligationService.REVIEWER_ASSIGNMENT,
                "Evaluations locked by review assistant");

        if (taskId == null)
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "status", "SECTION_SUBMITTED", "sectionInstanceId", sectionInstanceId, "taskApproved", false)));

        AssistantGate gate = approveAssistantTaskIfAllSubmitted(assessmentId, userId, taskId);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", gate.approved() ? "TASK_APPROVED" : "SECTION_SUBMITTED",
                "sectionInstanceId", sectionInstanceId,
                "submittedSections", gate.submitted(),
                "totalSections", gate.total(),
                "taskApproved", gate.approved())));
    }

    /** Result of the review-assistant sub-task gate — see the method below. */
    private record AssistantGate(long total, long submitted, boolean approved) {}

    /**
     * Approves the review assistant's sub-task once every section carrying
     * their assigned questions has been submitted, and reports the counts it
     * used so the response body does not run the two COUNTs again.
     *
     * The mirror of AssessmentController.approveContributorTaskIfAllSubmitted,
     * and extracted for the same reason: the ALREADY_SUBMITTED path above has
     * to re-evaluate this gate rather than skip it.
     *
     * Null taskId means there is no sub-task to gate (the clarification route,
     * where the assistant's task is already approved) — zeros and false.
     */
    private AssistantGate approveAssistantTaskIfAllSubmitted(Long assessmentId, Long userId, Long taskId) {
        if (taskId == null) return new AssistantGate(0, 0, false);

        long total     = assistantSubmissionRepository
                .countDistinctSectionsWithAssignments(assessmentId, userId);
        long submitted = assistantSubmissionRepository.countByTaskInstanceId(taskId);
        boolean allDone = submitted >= total && total > 0;

        log.info("[ASSISTANT-SUBMIT] Gate check | userId={} | submitted={} | total={} | allDone={}",
                userId, submitted, total, allDone);

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
        return new AssistantGate(total, submitted, allDone);
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
                .navContext(String.format("{\"assigneeRoute\":\"/module/vendor_assessment/%d?tab=review&questionInstanceId=%d\",\"reviewerRoute\":\"/module/vendor_assessment/%d?tab=review&questionInstanceId=%d\",\"questionInstanceId\":%d}", assessmentId, questionInstanceId, assessmentId, questionInstanceId, questionInstanceId))
                .remediationType("CLARIFICATION")
                // The SAME ui_navigation rows the workflow tasks use, so one
                // resolver answers for a task and for an action item and a
                // route change is one row, not a code change plus a backfill.
                // nav_context stays for the per-item extras a nav row cannot
                // carry — which question to open, the openWork bypass.
                .navKey("org_assessment_review")
                .assignerNavKey("org_assessment_review")
                .build();
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
    @Operation(summary = "Reviewer → vendor remediation finding. Creates the finding, then escalates it to an "
            + "EXTERNAL Issue owned by the section responder (severity mapped, due date carried, CISO notified).")
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

        // Resolve the responder: section's assigned user (VENDOR_RESPONDER).
        // Remediation ownership always goes to the responder, never to the contributor directly.
        Long responderUserId = null;
        if (qi.getSectionInstanceId() != null) {
            responderUserId = sectionInstanceRepository.findById(qi.getSectionInstanceId())
                    .map(si -> si.getAssignedUserId())
                    .orElse(null);
        }

        ActionItem item = ActionItem.builder()
                .tenantId(tenantId).createdBy(userId)
                .assignedTo(responderUserId)
                .assignedGroupRole("VENDOR_RESPONDER")
                .sourceType(ActionItem.SourceType.COMMENT).sourceId(questionInstanceId)
                .entityType(ActionItem.EntityType.QUESTION_RESPONSE).entityId(questionInstanceId)
                // The parent and the vendor, which this builder never set.
                //
                // Without them the only route from a remediation back to its
                // assessment is parsing the assigneeRoute string out of
                // navContext — a route, not a foreign key. Three consequences,
                // all of which this fixes:
                //
                //   • CloseIssueAction can find the assessment to decrement
                //     when an escalated issue closes.
                //   • A findings view can query
                //     /v1/action-items?entityType=ASSESSMENT&entityId={id}
                //     and get these back. Today that returns nothing, because
                //     every remediation is stored against a question instance.
                //   • idx_ai_vendor starts earning its index — vendor-scoped
                //     action-item queries could not use it for anything raised
                //     from an assessment.
                //
                // Rows created before this deploys keep their nulls; a reader
                // that needs them can still fall back to the question-instance
                // query.
                .parentEntityType(ActionItem.EntityType.ASSESSMENT)
                .parentEntityId(assessmentId)
                .vendorId(assessment.getVendorId())
                .title("Remediation required: " + qi.getQuestionTextSnapshot().substring(0, Math.min(80, qi.getQuestionTextSnapshot().length())))
                .description(description).status(ActionItem.Status.OPEN).priority(priority).dueAt(dueAt)
                .resolutionReservedFor(userId)
                .navContext(String.format(
                        "{\"assigneeRoute\":\"/module/vendor_assessment/%d?tab=fill&openWork=1&questionInstanceId=%d\"," +
                                "\"reviewerRoute\":\"/module/vendor_assessment/%d?tab=review&questionInstanceId=%d\"," +
                                "\"questionInstanceId\":%d}",
                        assessmentId, questionInstanceId, assessmentId, questionInstanceId, questionInstanceId))
                .remediationType("REMEDIATION_REQUEST").severity(severity)
                // The SAME ui_navigation rows the workflow tasks use, so one
                // resolver answers for a task and for an action item and a
                // route change is one row, not a code change plus a backfill.
                // nav_context stays for the per-item extras a nav row cannot
                // carry — which question to open, the openWork bypass.
                // A remediation is the one item whose two audiences differ by
                // SIDE: the vendor fixes it, the organisation validates it.
                .navKey("vendor_assessment_fill")
                .assignerNavKey("org_assessment_review")
                .expectedEvidence(expectedEvidence.isBlank() ? null : expectedEvidence)
                .build();
        actionItemRepository.save(item);

        assessment.setOpenRemediationCount(
                (assessment.getOpenRemediationCount() != null ? assessment.getOpenRemediationCount() : 0) + 1);
        assessmentRepository.save(assessment);

        String msg = resolveUserName(userId) + " flagged a question for remediation [" + severity + "]";
        if (responderUserId != null)
            notificationService.send(responderUserId, "REMEDIATION_REQUESTED", msg, "QUESTION_RESPONSE", questionInstanceId);
        if (qi.getAssignedUserId() != null && !qi.getAssignedUserId().equals(responderUserId))
            notificationService.send(qi.getAssignedUserId(), "REMEDIATION_REQUESTED", msg, "QUESTION_RESPONSE", questionInstanceId);
        notifyCiso(assessment, msg, questionInstanceId);

        // ── Escalate to an Issue ────────────────────────────────────────────
        //
        // This is the whole reason a remediation is now a finding rather than a
        // lifecycle of its own. The ActionItem stays as the finding record —
        // the same part AuditFinding plays on the audit side, and what the
        // Findings tab, the vendor scoping and the closure cascade all key on —
        // while the Issue carries the remediate/evidence/validate/close
        // workflow the vendor actually works in.
        //
        // Published, not called. AssessmentGuardFindingListener picks it up
        // AFTER_COMMIT and escalates; see the field comment on eventPublisher
        // for why that boundary matters. If escalation fails or the finding has
        // no owner, the listener logs it and the finding stands, escalatable by
        // hand from the Findings tab — which is why Escalate stays on that
        // screen as the recovery path rather than the normal one.
        eventPublisher.publishEvent(new ActionItemCreatedEvent(item, tenantId));

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

        // ── assessment_reports IS THE LIST; DOCUMENTS ARE THE FILES ──────────
        //
        // This used to read document_links alone, which meant it listed only
        // versions that had a PDF — and since no PDF generator exists, that was
        // none of them, ever. "No report has been generated yet" was shown for
        // assessments whose workflow had completed and whose report data had
        // been computed twice over.
        //
        // The version list now comes from assessment_reports, which is written
        // on every generation, with or without a file. A Document is paired in
        // by version when one exists, and that is what supplies downloadUrl.
        List<AssessmentReport> versions =
                assessmentReportRepository.findByAssessmentIdOrderByReportVersionDesc(assessmentId);

        // Documents for this assessment, indexed by version. Usually empty.
        List<DocumentLink> links = documentLinkRepository.findReportVersions("ASSESSMENT", assessmentId);
        List<Long> documentIds = links.stream().map(DocumentLink::getDocumentId).toList();
        Map<Long, Document> documentsById = documentIds.isEmpty() ? Map.of()
                : documentRepository.findAllById(documentIds).stream()
                  .collect(java.util.stream.Collectors.toMap(Document::getId, d -> d));
        Map<Integer, Document> docByVersion = new LinkedHashMap<>();
        documentsById.values().forEach(d -> {
            if (d.getVersion() != null) docByVersion.put(d.getVersion(), d);
        });

        Set<Long> generatorIds = versions.stream()
                .map(AssessmentReport::getGeneratedBy).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, String> generatorNameById = userDisplayNameService.resolveNames(generatorIds);

        List<Map<String, Object>> reports = versions.stream()
                .map(v -> {
                    Document doc = v.getReportVersion() != null
                            ? docByVersion.get(v.getReportVersion()) : null;

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reportId",        v.getId());
                    m.put("documentId",      doc != null ? doc.getId() : null);
                    m.put("reportVersion",   v.getReportVersion());
                    m.put("generatedAt",     v.getGeneratedAt() != null ? v.getGeneratedAt().toString() : "");
                    m.put("generatedByName", generatorNameById.get(v.getGeneratedBy()));
                    // PENDING is the honest status for a version with numbers
                    // and no file. It is not a failure and not "in progress" —
                    // it is what every version is until a PDF generator exists.
                    m.put("status",          doc != null ? doc.getStatus() : "PENDING");
                    m.put("pdfGenerated",    doc != null);

                    m.put("compliancePct",          v.getCompliancePct());
                    m.put("totalEarnedScore",       v.getTotalEarnedScore());
                    m.put("totalPossibleScore",     v.getTotalPossibleScore());
                    m.put("riskRating",             v.getRiskRating());
                    m.put("openRemediationCount",   v.getOpenRemediationCount());
                    m.put("openClarificationCount", v.getOpenClarificationCount());
                    m.put("triggerEvent",           v.getTriggerEvent());
                    m.put("remarks",                v.getRemarks());

                    String downloadUrl = null;
                    if (doc != null && doc.getS3Key() != null && "ACTIVE".equals(doc.getStatus())) {
                        try {
                            downloadUrl = storageService.generateDownloadUrl(
                                    doc.getS3Key(), false, doc.getFileName());
                        } catch (Exception e) {
                            log.warn("[REPORTS] presign failed for docId={}: {}", doc.getId(), e.getMessage());
                        }
                    }
                    m.put("downloadUrl", downloadUrl);
                    return m;
                })
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
        // Use reviewer-adjusted score: PASS → full, PARTIAL → 50%, FAIL → 0, PENDING → full.
        double earned   = responseRepository.sumReviewerAdjustedScoreByAssessmentId(assessmentId);
        double pct      = possible > 0 ? Math.round((earned / possible) * 10000.0) / 100.0 : 0.0;

        int openRemed = countOpenItemsByType(assessmentId, tenantId, "REMEDIATION_REQUEST");
        int openClar  = countOpenItemsByType(assessmentId, tenantId, "CLARIFICATION");

        // ── VERSION COUNTS assessment_reports, NOT document_links ────────────
        //
        // It used to count REPORT document_links, and that was the first half
        // of why this endpoint could never work. See the block below for the
        // second half; the short version is that no Document is ever created,
        // so this always returned 0 and every report was "v1" forever.
        int version = (int) assessmentReportRepository.countByAssessmentId(assessmentId) + 1;

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

        // ── THE REPORT IS A ROW IN assessment_reports ────────────────────────
        //
        // WHY THIS CHANGED, because it is the whole reason "Generate report"
        // returned 409 and the Reports tab was empty.
        //
        // Document.s3_key is declared @Column(nullable = false). There is no
        // PDF generator in this codebase — pdfBytes is `new byte[0]` here and
        // in GenerateAssessmentReportAction, both marked as stubs — so the
        // upload block above never runs and s3Key is always null. Saving this
        // Document therefore violated NOT NULL on every single call, and the
        // global handler rendered that DataIntegrityViolationException as
        //
        //   409 — "This action conflicts with an existing record (a value that
        //          must be unique is already in use)"
        //
        // which is the wrong diagnosis: nothing was duplicated, a required
        // column was null. That message is why this looked like a data clash
        // rather than a missing file.
        //
        // Meanwhile the automatic path does the opposite and skips Document
        // creation entirely when there are no PDF bytes — correct, but it
        // recorded the version NOWHERE, so a completed workflow produced no
        // report at all while appearing to succeed.
        //
        // assessment_reports is the table for this. It already exists, its
        // entity javadoc says in as many words "report_url: null until PDF
        // generation is implemented", it carries every number a report version
        // needs, and nothing has ever written to it. The report DATA — the
        // compliance percentage, the scores, the risk rating, the open counts
        // frozen at that moment — is real and computed; only the rendering is
        // missing. So the version is always recorded, and the Document and its
        // link are created only when there is an actual file to point at.
        AssessmentReport report = AssessmentReport.builder()
                .tenantId(tenantId)
                .assessmentId(assessmentId)
                .reportVersion(version)
                .generatedAt(LocalDateTime.now())
                .generatedBy(userId)
                .totalEarnedScore(earned)
                .totalPossibleScore(possible)
                .compliancePct(pct)
                .riskRating(assessment.getRiskRating())
                .openRemediationCount(openRemed)
                .openClarificationCount(openClar)
                .triggerEvent(triggerEvent)
                .remarks(remarks == null || remarks.isBlank() ? null : remarks)
                .build();
        assessmentReportRepository.save(report);

        Long reportDocId = null;
        if (s3Key != null) {
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
                    .s3Bucket(storageService.getBucket())
                    .storagePath(s3Key)
                    .status("ACTIVE")
                    .version(version)
                    .contentLength(uploadResult != null ? uploadResult.getContentLength() : 0L)
                    .checksumSha256(uploadResult != null ? uploadResult.getChecksumSha256() : null)
                    .build();
            documentRepository.save(reportDoc);
            reportDocId = reportDoc.getId();

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

            // So getReports can pair the version with its file without
            // guessing, and so a future PDF generator has one place to fill in.
            report.setReportUrl(s3Key);
            assessmentReportRepository.save(report);
        }

        assessment.setOpenRemediationCount(openRemed);
        assessment.setReportVersion(version);
        assessmentRepository.save(assessment);

        log.info("[REPORT] v{} | assessmentId={} | reportRowId={} | docId={} | {}% | openRemed={} | trigger={}",
                version, assessmentId, report.getId(), reportDocId, pct, openRemed, triggerEvent);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId",              report.getId());
        out.put("documentId",            reportDocId);
        // Null documentId is not an error — it means the version was recorded
        // and no PDF exists to download. The client says so rather than
        // offering a link that cannot resolve.
        out.put("pdfGenerated",          reportDocId != null);
        out.put("reportVersion",         version);
        out.put("compliancePct",         pct);
        out.put("totalEarnedScore",      earned);
        out.put("totalPossibleScore",    possible);
        out.put("riskRating",            assessment.getRiskRating() != null ? assessment.getRiskRating() : "");
        out.put("openRemediationCount",  openRemed);
        out.put("openClarificationCount",openClar);
        out.put("triggerEvent",          triggerEvent);
        return out;
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

    /** Delegates to UserDisplayNameService (batch + Redis-cached) — see that
     * class for why single-id resolution now hits Redis instead of MySQL on
     * repeat calls. Call sites in a loop should prefer resolveNames() (batch)
     * over N calls to this — see getReports/getMyReviewerSections below. */
    private String resolveUserName(Long userId) {
        return userDisplayNameService.resolveName(userId);
    }
}