package com.kashi.grc.assessment.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.assessment.domain.AssessmentOptionInstance;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.AssessmentResponse;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.dto.request.AnswerRequest;
import com.kashi.grc.assessment.repository.AssessmentOptionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentResponseRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.guard.service.GuardEvaluator;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Submitting or updating one answer.
 *
 * ── EXTRACTED FROM AssessmentController.submitAnswer, LINES 425–712 ───────
 * 292 lines doing seven things: gating access, unlocking the section,
 * transitioning the assessment, scoring, persisting, running KashiGuard, and
 * moving action items. They are still done in that order, for the same
 * reasons, with the same error codes and the same response shape.
 *
 * What moved out rather than changed:
 *   · The access gate and the section lock → AssessmentAccessGuard, because
 *     every other endpoint needs the same rule and there was no way to reach it.
 *   · The scoring formula → AssessmentScoringService, because it is the
 *     arithmetic behind a customer-visible compliance percentage and it
 *     deserves to be testable without a database.
 *
 * What genuinely changed:
 *   · QUERY COUNT. A 5-option multi-select cost eleven queries against the
 *     same handful of option rows — the sum helper loads every option, then a
 *     findById per selected option, then another findById per option in the
 *     guard block. Now one read, passed to both.
 *   · THE SHARED ObjectMapper instead of three `new ObjectMapper()`.
 *   · TENANT SCOPING on the assessment read. See loadAssessment.
 *   · CLEARING AN ANSWER NOW CLEARS ITS SCORE. The one score-affecting
 *     change; see AssessmentScoringService for why the old behaviour was
 *     already inconsistent with itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentAnswerService {

    private final VendorAssessmentRepository           assessmentRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final AssessmentOptionInstanceRepository   optionInstanceRepository;
    private final AssessmentResponseRepository         responseRepository;
    private final ActionItemRepository                 actionItemRepository;
    private final UserRepository                       userRepository;
    private final AssessmentAccessGuard                accessGuard;
    private final AssessmentScoringService             scoring;
    private final GuardEvaluator                       guardEvaluator;
    private final NotificationService                  notificationService;
    private final UtilityService                       utilityService;

    private static final String CONTRIBUTOR_ASSIGNMENT = "CONTRIBUTOR_ASSIGNMENT";
    private static final String REMEDIATION_REQUEST    = "REMEDIATION_REQUEST";

    // ═════════════════════════════════════════════════════════════════════════
    // THE WRITE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> submit(Long assessmentId, AnswerRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        VendorAssessment assessment = loadAssessment(assessmentId, tenantId);

        // Order preserved: who-may-act, then is-it-locked, then is-it-editable.
        // Each answers a different question and the messages differ, so a user
        // blocked for one reason is never told the other.
        accessGuard.assertCanAct(assessment, userId, "submit answer");
        accessGuard.assertAnswerEditable(req.getQuestionInstanceId(), userId, tenantId);
        assertAssessmentEditable(assessment);

        markInProgress(assessment);

        AssessmentQuestionInstance qi = (req.getQuestionInstanceId() == null) ? null
                : questionInstanceRepository.findById(req.getQuestionInstanceId()).orElse(null);
        double weight = (qi != null && qi.getWeight() != null) ? qi.getWeight() : 1.0;

        // One read, used for scoring AND for the guard's option text. The
        // original queried these rows twice over, once per option each time.
        List<AssessmentOptionInstance> options = (req.getQuestionInstanceId() == null)
                ? Collections.emptyList()
                : optionInstanceRepository.findByQuestionInstanceIdOrderByOrderNo(
                        req.getQuestionInstanceId());

        AssessmentScoringService.ScoredAnswer scored = scoring.score(
                req, qi == null ? null : qi.getResponseType(), options, weight);

        persist(tenantId, assessmentId, req.getQuestionInstanceId(), scored, userId);

        runGuard(assessment, qi, scored, options, tenantId);
        advanceActionItems(req.getQuestionInstanceId(), userId, tenantId);

        return result(assessmentId, req.getQuestionInstanceId(), scored);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STEPS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Loads the assessment, scoped to the caller's tenant.
     *
     * The original used a bare findById with no tenant check, relying on the
     * task guard to catch a cross-tenant caller indirectly. It would have, in
     * practice — but the upsert below stamps the row with the CALLER's tenant
     * id, so anything that ever slipped past the guard would have written a
     * response belonging to one tenant onto another tenant's assessment. A
     * mis-tenanted row is far harder to find later than a 404 now.
     *
     * This matches the sibling endpoint — /v1/assessments/{id}/submit already
     * uses findByIdAndTenantId — so a legitimate caller sees no difference. And
     * 404 rather than 403: a 403 would confirm the id exists in some other
     * tenant.
     */
    private VendorAssessment loadAssessment(Long assessmentId, Long tenantId) {
        return assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
    }

    private void assertAssessmentEditable(VendorAssessment assessment) {
        String status = assessment.getStatus();
        if (!"ASSIGNED".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw new BusinessException("ASSESSMENT_NOT_EDITABLE",
                    "Cannot edit assessment in status: " + status);
        }
    }

    /** First answer moves the assessment off ASSIGNED. Preserved exactly. */
    private void markInProgress(VendorAssessment assessment) {
        if ("ASSIGNED".equals(assessment.getStatus())) {
            assessment.setStatus("IN_PROGRESS");
            assessmentRepository.save(assessment);
        }
    }

    /**
     * Writes the answer via the native upsert.
     *
     * ── WHY NOT save() ────────────────────────────────────────────────────
     * Preserved verbatim in intent, and the reasoning is worth keeping in
     * front of anyone who edits this. When save() throws
     * DataIntegrityViolationException inside a @Transactional method, Hibernate
     * marks the session rollback-only; the next query in that transaction then
     * flushes the broken entity (id = null) and crashes with AssertionFailure.
     * So the obvious save-then-catch-then-retry is not merely slower, it
     * poisons the session.
     *
     * INSERT ... ON DUPLICATE KEY UPDATE is atomic in the database. Two
     * concurrent clicks both succeed — one inserts, the other updates — and the
     * Hibernate session stays clean throughout. Do not replace this with save().
     */
    private void persist(Long tenantId, Long assessmentId, Long questionInstanceId,
                         AssessmentScoringService.ScoredAnswer scored, Long userId) {
        responseRepository.upsertResponse(
                tenantId,
                assessmentId,
                questionInstanceId,
                scored.responseText(),
                scored.selectedOptionInstanceId(),
                scored.scoreEarned(),
                userId,
                LocalDateTime.now());
    }

    /**
     * KashiGuard evaluation.
     *
     * @Async on the evaluator, so this returns immediately and the rules run on
     * another thread in their own transaction. It is passed values rather than
     * ids for exactly that reason — it must not depend on seeing a row this
     * transaction has not committed yet.
     */
    private void runGuard(VendorAssessment assessment,
                          AssessmentQuestionInstance qi,
                          AssessmentScoringService.ScoredAnswer scored,
                          List<AssessmentOptionInstance> options,
                          Long tenantId) {
        if (qi == null) return;

        String navCtx = String.format(
                "{\"assigneeRoute\":\"/vendor/assessments/%d/fill?openWork=1\","
                        + "\"reviewerRoute\":\"/vendor/assessments/%d/responder-review\","
                        + "\"questionInstanceId\":%d}",
                assessment.getId(), assessment.getId(), qi.getId());

        guardEvaluator.evaluate(
                qi.getQuestionTagSnapshot(),
                qi.getId(),
                scored.responseText(),
                // Real-time submit never carries a file; uploads are evaluated
                // separately through DocumentLink. Preserved.
                false,
                scored.scoreEarned(),
                navCtx,
                scoring.selectedOptionValues(qi.getResponseType(),
                        scored.selectedOptionInstanceId(), scored.responseText(), options),
                tenantId);
    }

    /**
     * Re-answering moves the ball back to the other side.
     *
     * Three outcomes, preserved exactly:
     *   CONTRIBUTOR_ASSIGNMENT → RESOLVED. Answering the question IS the work;
     *       there is nobody waiting to accept it.
     *   REMEDIATION_REQUEST    → PENDING_VALIDATION.
     *   everything else        → PENDING_REVIEW.
     *
     * Note what does NOT happen: nothing is auto-resolved for the latter two.
     * A reviewer has to accept, because a contributor declaring their own fix
     * good is the thing this loop exists to prevent.
     */
    private void advanceActionItems(Long questionInstanceId, Long userId, Long tenantId) {
        if (questionInstanceId == null) return;

        List<ActionItem> items = actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedTo(userId))
                        .and(ActionItemSpecification.forEntity(
                                ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                        .and(ActionItemSpecification.open()));

        for (ActionItem ai : items) {
            if (ai.getStatus() != ActionItem.Status.OPEN
                    && ai.getStatus() != ActionItem.Status.IN_PROGRESS) continue;

            if (CONTRIBUTOR_ASSIGNMENT.equals(ai.getRemediationType())) {
                ai.setStatus(ActionItem.Status.RESOLVED);
                ai.setResolutionNote("Question answered by contributor");
                ai.setResolvedAt(LocalDateTime.now());
                ai.setResolvedBy(userId);
                actionItemRepository.save(ai);
                continue;
            }

            boolean isRemediation = REMEDIATION_REQUEST.equals(ai.getRemediationType());
            ai.setStatus(isRemediation
                    ? ActionItem.Status.PENDING_VALIDATION
                    : ActionItem.Status.PENDING_REVIEW);
            actionItemRepository.save(ai);

            notifyWaitingReviewer(ai, userId, questionInstanceId, isRemediation);

            log.info("[ACTION-ITEM] → {} after re-answer | id={} | qi={} | type={}",
                    ai.getStatus(), ai.getId(), questionInstanceId,
                    ai.getRemediationType() != null ? ai.getRemediationType() : "REVISION_REQUEST");
        }
    }

    /**
     * Tells whoever is waiting that there is something to look at.
     *
     * resolutionReservedFor first, createdBy as the fallback — the person who
     * reserved the resolution is the one who has to act; the person who raised
     * it only knows it exists. Never notifies the actor about their own action.
     */
    private void notifyWaitingReviewer(ActionItem ai, Long userId,
                                       Long questionInstanceId, boolean isRemediation) {
        Long reviewerId = ai.getResolutionReservedFor() != null
                ? ai.getResolutionReservedFor() : ai.getCreatedBy();
        if (reviewerId == null || reviewerId == 0L || reviewerId.equals(userId)) return;

        String actor = userRepository.findById(userId)
                .map(u -> {
                    String fn = u.getFirstName() != null ? u.getFirstName() : "";
                    String ln = u.getLastName()  != null ? u.getLastName()  : "";
                    String full = (fn + " " + ln).trim();
                    return full.isEmpty() ? u.getEmail() : full;
                })
                .orElse("A contributor");

        String title = ai.getTitle() == null ? "" : ai.getTitle();
        String shortTitle = title.substring(0, Math.min(80, title.length()));

        notificationService.send(
                reviewerId,
                isRemediation ? "REMEDIATION_PENDING_VALIDATION" : "ACTION_ITEM_PENDING_REVIEW",
                isRemediation
                        ? actor + " submitted remediation for validation: " + shortTitle
                        : actor + " submitted for review: " + shortTitle,
                "QUESTION_RESPONSE",
                questionInstanceId);
    }

    /**
     * The response body, shape for shape with the original.
     *
     * responseId is re-fetched because upsertResponse is a void native query —
     * it does not populate the entity's id, and the client needs the real one.
     *
     * ── scoreEarned IS REPORTED AS 0.0 WHEN STORED AS NULL, ON PURPOSE ────
     * The stored value stays null for FILE_UPLOAD, which is correct: that
     * question is scored elsewhere, via DocumentLink, and SQL SUM ignores null
     * so the aggregate is right.
     *
     * The reported value keeps the original's 0.0 default anyway. I had made
     * this null to "say the true thing", and that was the wrong call for a
     * parallel run: a client doing arithmetic on the field — toFixed, a
     * progress bar, anything — breaks on null, and it would break only on file
     * questions, which is the kind of bug that surfaces a week later in
     * somebody's demo. The client gains nothing from the distinction; the
     * database is where it matters, and there it is preserved.
     *
     * LinkedHashMap rather than Map.of regardless: Map.of rejects nulls, and
     * questionInstanceId can be null on a malformed request.
     */
    private Map<String, Object> result(Long assessmentId, Long questionInstanceId,
                                       AssessmentScoringService.ScoredAnswer scored) {
        Long responseId = responseRepository
                .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(
                        assessmentId, questionInstanceId)
                .map(AssessmentResponse::getId)
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("responseId",         responseId != null ? responseId : 0L);
        out.put("assessmentId",       assessmentId);
        out.put("questionInstanceId", questionInstanceId);
        out.put("scoreEarned",        scored.scoreEarned() != null ? scored.scoreEarned() : 0.0);
        return out;
    }
}
