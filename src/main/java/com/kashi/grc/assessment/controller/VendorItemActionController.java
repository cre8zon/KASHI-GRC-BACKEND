package com.kashi.grc.assessment.controller;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.AssessmentResponse;
import com.kashi.grc.assessment.repository.AssessmentOptionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentResponseRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.comment.domain.EntityComment;
import com.kashi.grc.comment.service.CommentService;
import com.kashi.grc.comment.dto.CommentRequest;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * VendorItemActionController — responder→contributor command actions.
 *
 * ISOLATION RULE: This controller handles ONLY the two actions that cannot
 * go through the centralized CommentController:
 *
 *   1. ACCEPT contributor answer   — stamps reviewerStatus = ACCEPTED
 *   2. OVERRIDE contributor answer — saves responder's own answer, preserves
 *                                    original as a SYSTEM comment via CommentService
 *
 * REVISION REQUEST deliberately goes through POST /v1/comments with
 * commentType = REVISION_REQUEST. CommentService.handleRevisionRequest()
 * already auto-creates the ActionItem for the contributor. Nothing new needed.
 *
 * System audit events (accept/override) use CommentService.addSystemComment()
 * — same table, same WebSocket push, same visibility rules. No new tables.
 *
 * ── SCALABILITY NOTE ────────────────────────────────────────────────────────
 * The Accept/Override pattern will apply identically to Controls, Risks,
 * Policies, and Issues in future modules. When those are built:
 *   - Add the entity-specific status update (equivalent to updateResponderStatus)
 *   - Call commentService.addSystemComment() for audit trail
 *   - Keep this controller focused on ASSESSMENT questions only;
 *     create ControlItemActionController etc. for other modules.
 * ────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@RestController
@Tag(name = "Vendor Item Actions", description = "Responder accept/override actions on contributor answers")
@RequiredArgsConstructor
@RequestMapping("/v1/assessments")
public class VendorItemActionController {

    private final VendorAssessmentRepository              assessmentRepository;
    private final AssessmentQuestionInstanceRepository    questionInstanceRepository;
    private final AssessmentResponseRepository            responseRepository;
    private final AssessmentOptionInstanceRepository      optionInstanceRepository;
    private final ActionItemRepository                    actionItemRepository;
    private final UserRepository                          userRepository;
    private final CommentService                          commentService;
    private final NotificationService                     notificationService;
    private final UtilityService                          utilityService;

    // ══════════════════════════════════════════════════════════════════════
    // 1. ACCEPT CONTRIBUTOR ANSWER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Responder accepts contributor's submitted answer.
     *
     * Effects:
     *   - Sets reviewerStatus = ACCEPTED on the response row
     *   - Closes any open REVISION_REQUEST action items for this question
     *   - Logs a SYSTEM comment (audit trail, visible in ItemPanel activity tab)
     *   - Notifies contributor
     *
     * Idempotent: calling twice on an already-accepted answer is a no-op.
     */
    @PutMapping("/{assessmentId}/questions/{questionInstanceId}/accept-contributor")
    @Transactional
    @Operation(summary = "Responder accepts contributor's submitted answer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptContributorAnswer(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        if (!qi.getAssessmentId().equals(assessmentId))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "QuestionInstance does not belong to assessment " + assessmentId);
        if (qi.getAssignedUserId() == null)
            throw new BusinessException("NO_CONTRIBUTOR", "No contributor is assigned to this question.");

        responseRepository.findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(
                        assessmentId, questionInstanceId)
                .orElseThrow(() -> new BusinessException("NO_RESPONSE",
                        "Contributor has not submitted an answer yet."));

        // Already accepted — idempotent
        responseRepository.updateResponderStatus(assessmentId, questionInstanceId, "ACCEPTED");

        // Close any open REVISION_REQUEST action items on this question
        actionItemRepository.findAll(
                        ActionItemSpecification.forTenant(tenantId)
                                .and(ActionItemSpecification.forEntity(
                                        ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                                .and(ActionItemSpecification.open())
                ).stream()
                .filter(ai -> "REVISION_REQUEST".equals(ai.getRemediationType()))
                .forEach(ai -> {
                    ai.setStatus(ActionItem.Status.RESOLVED);
                    ai.setResolvedAt(LocalDateTime.now());
                    ai.setResolvedBy(userId);
                    ai.setResolutionNote("Answer accepted by " + resolveUserName(userId));
                    actionItemRepository.save(ai);
                });

        // Audit trail via centralized comment module — shows in ItemPanel activity tab
        commentService.addSystemComment(
                EntityComment.EntityType.QUESTION_RESPONSE,
                questionInstanceId,
                tenantId,
                "Answer accepted by " + resolveUserName(userId));

        // Notify contributor
        if (qi.getAssignedUserId() != null && !qi.getAssignedUserId().equals(userId))
            notificationService.send(qi.getAssignedUserId(), "ANSWER_ACCEPTED",
                    resolveUserName(userId) + " accepted your answer",
                    "QUESTION_RESPONSE", questionInstanceId);

        log.info("[ACCEPT] qi={} | by={} | assessmentId={}", questionInstanceId, userId, assessmentId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "questionInstanceId", questionInstanceId,
                "responderStatus",    "ACCEPTED",
                "acceptedByName",     resolveUserName(userId))));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. OVERRIDE CONTRIBUTOR ANSWER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Responder overrides contributor's answer with their own.
     *
     * Effects:
     *   - Preserves original answer as a SYSTEM comment before overwriting
     *   - Calls upsertResponse with responder's text/option
     *   - Sets reviewerStatus = OVERRIDDEN
     *   - Logs a second SYSTEM comment noting the override + reason
     *   - Notifies contributor
     *
     * Body: { responseText?, selectedOptionInstanceId?, overrideReason? }
     *
     * Note: revision request goes through POST /v1/comments (CommentService handles
     * the ActionItem side effects automatically). No endpoint needed here.
     */
    @PostMapping("/{assessmentId}/questions/{questionInstanceId}/override-answer")
    @Transactional
    @Operation(summary = "Responder overrides contributor's answer — original preserved in audit trail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overrideContributorAnswer(
            @PathVariable Long assessmentId,
            @PathVariable Long questionInstanceId,
            @RequestBody Map<String, Object> body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        String overrideText   = body.get("responseText") != null ? body.get("responseText").toString() : null;
        Long   selectedOptId  = body.get("selectedOptionInstanceId") != null
                ? Long.parseLong(body.get("selectedOptionInstanceId").toString()) : null;
        String overrideReason = body.getOrDefault("overrideReason", "").toString().trim();

        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("QuestionInstance", questionInstanceId));
        if (!qi.getAssessmentId().equals(assessmentId))
            throw new com.kashi.grc.common.exception.ValidationException(
                    "QuestionInstance does not belong to assessment " + assessmentId);

        // Preserve original answer as SYSTEM comment before overwriting
        responseRepository.findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(
                        assessmentId, questionInstanceId)
                .ifPresent(orig -> {
                    String originalText = orig.getResponseText() != null
                            ? orig.getResponseText() : "[choice answer — id:" + orig.getSelectedOptionInstanceId() + "]";
                    String originalBy   = resolveUserName(orig.getSubmittedBy());
                    commentService.addSystemComment(
                            EntityComment.EntityType.QUESTION_RESPONSE,
                            questionInstanceId, tenantId,
                            "Original answer by " + originalBy + ": " + truncate(originalText, 200));
                });

        // Calculate score for override option if a choice was selected
        Double scoreEarned = null;
        if (selectedOptId != null)
            scoreEarned = optionInstanceRepository.findById(selectedOptId)
                    .map(opt -> opt.getScore()).orElse(null);

        // Write override via native upsert (safe, no session poisoning)
        responseRepository.upsertResponse(
                tenantId, assessmentId, questionInstanceId,
                overrideText, selectedOptId,
                scoreEarned, userId, LocalDateTime.now());

        // Stamp OVERRIDDEN status
        responseRepository.updateResponderStatus(assessmentId, questionInstanceId, "OVERRIDDEN");

        // Log override event
        String auditMsg = "Answer overridden by " + resolveUserName(userId)
                + (overrideReason.isBlank() ? "" : " — reason: " + overrideReason);
        commentService.addSystemComment(
                EntityComment.EntityType.QUESTION_RESPONSE,
                questionInstanceId, tenantId, auditMsg);

        // Notify contributor
        if (qi.getAssignedUserId() != null && !qi.getAssignedUserId().equals(userId))
            notificationService.send(qi.getAssignedUserId(), "ANSWER_OVERRIDDEN",
                    resolveUserName(userId) + " overrode your answer for a question",
                    "QUESTION_RESPONSE", questionInstanceId);

        log.info("[OVERRIDE] qi={} | by={} | assessmentId={}", questionInstanceId, userId, assessmentId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "questionInstanceId", questionInstanceId,
                "responderStatus",    "OVERRIDDEN",
                "overriddenByName",   resolveUserName(userId))));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveUserName(Long userId) {
        if (userId == null) return "Unknown";
        return userRepository.findById(userId).map(u -> {
            String fn = u.getFirstName() != null ? u.getFirstName() : "";
            String ln = u.getLastName()  != null ? u.getLastName()  : "";
            String full = (fn + " " + ln).trim();
            return full.isEmpty() ? u.getEmail() : full;
        }).orElse("Unknown");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
