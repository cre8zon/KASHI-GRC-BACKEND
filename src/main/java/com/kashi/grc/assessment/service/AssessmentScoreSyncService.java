package com.kashi.grc.assessment.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentResponseRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Re-derives an assessment's denormalised score fields from their sources.
 *
 * This is the vendor-assessment counterpart of
 * AuditTestPolicySnapshotService.syncEngagementScore, which audit calls after
 * any test-result change or finding status change. Same idea, same reason to
 * exist: totalEarnedScore, totalPossibleScore and openRemediationCount are
 * cached numbers that appear in the overview KPIs, the response DTO and the
 * generated report, so when they are wrong a customer sees it.
 *
 * ── WHY RE-DERIVE RATHER THAN ADJUST ──────────────────────────────────────
 *
 * CloseIssueAction used to do the counter half as arithmetic:
 *
 *     int next = Math.max(0, cur - 1);
 *
 * Three things go wrong with that, and all three disappear here.
 *
 *   1. It drifts. A decrement-only counter has no way back once anything else
 *      resolves an item — a bulk operation, a dismissal, a manual fix — and
 *      Math.max(0, …) hides the drift at the bottom instead of reporting it.
 *
 *   2. It silently did nothing for rows created before parentEntityId was
 *      stamped on the remediation builder. That branch logged a WARN and
 *      returned, so old assessments kept counting remediations that had closed.
 *      The count below resolves those rows the slow-but-correct way instead
 *      (see legacy handling in countOpenRemediations).
 *
 *   3. It cannot tell the two closure routes apart, and they differ. A
 *      remediation closed because the vendor fixed the answer and the
 *      organisation validated it SHOULD move the score, because the answer
 *      changed. One closed by accepting the risk should NOT, because nothing
 *      about the answer changed — but the open count still has to come down.
 *      Re-deriving both from source gets that right without this service ever
 *      knowing which route was taken, which is the point: there is no branch
 *      to get wrong later.
 *
 * ── WHAT IT DELIBERATELY DOES NOT TOUCH ───────────────────────────────────
 *
 *   • Vendor.currentRiskScore — that is INHERENT risk, computed by
 *     VendorRiskService.calculate() from dataAccessLevel, riskClassification,
 *     criticality and industry. Closing a remediation does not change a
 *     vendor's data access level, so the number must not move. A residual
 *     score that reacts to assessment outcomes is a separate concept and needs
 *     its own column and its own mapping; inventing one here would quietly
 *     overwrite the inherent score with something else.
 *
 *   • VendorAssessment.riskRating — assigned by a human, at workflow step 11,
 *     through POST /v1/assessments/{id}/risk-rating, validated against
 *     LOW/MEDIUM/HIGH/CRITICAL. It is not derived from the score, so there is
 *     nothing here to re-derive. If a closed remediation should be able to move
 *     the rating, that is a decision about whether the CISO's step reopens —
 *     not a sync.
 *
 *   • Report regeneration. When the open count reaches zero a new report
 *     version is due, and this logs that it is. Triggering it means lifting
 *     ReviewController.decrementAndMaybeReport and generateReportInternal (plus
 *     their six repositories) out of a controller, which is a real refactor of
 *     a working path and belongs in its own change. The Review screen already
 *     exposes "Re-generate", and POST /v1/assessments/{id}/generate-report is
 *     the same call.
 *
 * ── RELATIONSHIP TO AssessmentRemediationService.recount ──────────────────
 *
 * That method exists and looks like it does this job. It does not, for two
 * reasons that matter here:
 *
 *   • It reads the tenant from utilityService.getLoggedInDataContext(), and
 *     there is no logged-in context inside a workflow automation — this runs
 *     from CloseIssueAction, on a background thread, with no request.
 *   • Its belongsTo(item, assessmentId) returns true unconditionally, with a
 *     comment saying so, so it counts every open remediation in the TENANT and
 *     writes that number onto one assessment.
 *
 * So the tenant is a parameter here and the scoping is real. recount() should
 * eventually delegate to this; it is left alone for now because it is called
 * from a request path that works and changing it is not part of this change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentScoreSyncService {

    private final VendorAssessmentRepository            assessmentRepository;
    private final AssessmentResponseRepository          responseRepository;
    private final AssessmentQuestionInstanceRepository  questionInstanceRepository;
    private final ActionItemRepository                  actionItemRepository;

    /** What ReviewController.requestRemediation and GuardEvaluator stamp on a finding. */
    private static final String REMEDIATION_REQUEST = "REMEDIATION_REQUEST";

    /** An item in either of these is finished and must not be counted as open. */
    private static final Set<ActionItem.Status> TERMINAL =
            Set.of(ActionItem.Status.RESOLVED, ActionItem.Status.DISMISSED);

    /**
     * Recomputes totalEarnedScore, totalPossibleScore and openRemediationCount
     * for one assessment and persists them.
     *
     * Safe to call repeatedly — it writes derived values, so running it twice
     * produces the same row. Callers that are already inside a transaction join
     * it; CloseIssueAction is one of those.
     *
     * @param assessmentId the assessment to re-derive
     * @param tenantId     passed explicitly: there is no request context on the
     *                     paths that call this
     * @return the open remediation count after the sync, so a caller on a
     *         request path can report it without a second read. -1 when
     *         nothing was synced (unknown assessment, or wrong tenant) —
     *         distinguishable from a real zero, which matters because zero is
     *         the number that means "a report version is due".
     */
    @Transactional
    public int syncAssessmentScore(Long assessmentId, Long tenantId) {
        if (assessmentId == null || tenantId == null) return -1;

        VendorAssessment assessment = assessmentRepository.findById(assessmentId).orElse(null);
        if (assessment == null) {
            log.warn("[SCORE-SYNC] No assessment {} — nothing to sync", assessmentId);
            return -1;
        }
        if (!tenantId.equals(assessment.getTenantId())) {
            // Not an exception: this is called from a cascade, and refusing
            // loudly there would fail a closure that is otherwise correct.
            log.warn("[SCORE-SYNC] Tenant mismatch | assessmentId={} assessmentTenant={} callerTenant={}",
                    assessmentId, assessment.getTenantId(), tenantId);
            return -1;
        }

        // ── Scores, from the same two queries the report action uses ────────
        //
        // sumReviewerAdjustedScoreByAssessmentId applies the verdict weighting
        // (FAIL=0, PARTIAL=half, PASS/PENDING=full) in one SQL SUM and returns
        // 0.0 when nothing is scored. sumWeightByAssessmentId is the ceiling,
        // counting a null weight as 1.0 — the same rule, so the two agree and
        // the ratio cannot exceed 1.
        Double earned   = responseRepository.sumReviewerAdjustedScoreByAssessmentId(assessmentId);
        Double possible = questionInstanceRepository.sumWeightByAssessmentId(assessmentId);

        Double prevEarned   = assessment.getTotalEarnedScore();
        Double prevPossible = assessment.getTotalPossibleScore();
        int    prevOpen     = assessment.getOpenRemediationCount() == null
                ? 0 : assessment.getOpenRemediationCount();

        int open = countOpenRemediations(assessmentId, tenantId);

        assessment.setTotalEarnedScore(earned != null ? earned : 0.0);
        assessment.setTotalPossibleScore(possible != null ? possible : 0.0);
        assessment.setOpenRemediationCount(open);
        assessmentRepository.save(assessment);

        log.info("[SCORE-SYNC] assessmentId={} | earned {} -> {} | possible {} -> {} | openRemediation {} -> {}",
                assessmentId, prevEarned, assessment.getTotalEarnedScore(),
                prevPossible, assessment.getTotalPossibleScore(), prevOpen, open);

        if (open == 0 && prevOpen > 0) {
            log.info("[SCORE-SYNC] All remediations closed on assessmentId={} — a report version is due. "
                    + "Trigger it with POST /v1/assessments/{}/generate-report.", assessmentId, assessmentId);
        }
        return open;
    }

    /**
     * Open remediation findings belonging to this assessment.
     *
     * Two shapes of row, because the parent link was added partway through:
     *
     *   current — parentEntityType = ASSESSMENT, parentEntityId = assessmentId.
     *             Stamped by ReviewController.requestRemediation and by
     *             AssessmentGuardFindingListener before it escalates.
     *
     *   legacy  — parentEntityId null, entityType = QUESTION_RESPONSE and
     *             entityId pointing at a question instance. Resolved by loading
     *             this assessment's question-instance ids and matching on them.
     *             One extra query, bounded by the question count, and it is the
     *             same read the report action already does for weights.
     *
     * The id list is only used for the legacy arm, so once old rows are gone
     * the second predicate matches nothing and the arm costs one IN clause.
     */
    private int countOpenRemediations(Long assessmentId, Long tenantId) {
        List<Long> questionInstanceIds = questionInstanceRepository
                .findByAssessmentIdOrderByOrderNo(assessmentId)
                .stream()
                .map(AssessmentQuestionInstance::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Specification<ActionItem> spec = (root, query, cb) -> {
            Predicate mine = cb.and(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.equal(root.get("remediationType"), REMEDIATION_REQUEST),
                    cb.not(root.get("status").in(TERMINAL)));

            Predicate byParent = cb.and(
                    cb.equal(root.get("parentEntityType"), ActionItem.EntityType.ASSESSMENT),
                    cb.equal(root.get("parentEntityId"), assessmentId));

            if (questionInstanceIds.isEmpty()) {
                return cb.and(mine, byParent);
            }

            Predicate byQuestion = cb.and(
                    cb.isNull(root.get("parentEntityId")),
                    cb.equal(root.get("entityType"), ActionItem.EntityType.QUESTION_RESPONSE),
                    root.get("entityId").in(questionInstanceIds));

            return cb.and(mine, cb.or(byParent, byQuestion));
        };

        return (int) actionItemRepository.count(spec);
    }
}