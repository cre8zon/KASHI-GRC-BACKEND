package com.kashi.grc.assessment.repository;

import java.util.List;

/**
 * Custom repository extension for AssessmentResponseRepository.
 *
 * Declares every operation that requires JPA Criteria API — either because
 * it involves conditional aggregation, cross-entity joins without a mapped
 * relationship, bulk updates, or filtering on nullable fields.
 *
 * Spring Data wires this automatically via the "Impl" suffix convention:
 * AssessmentResponseRepositoryImpl satisfies this interface and Spring
 * injects it into AssessmentResponseRepository without any explicit config.
 *
 * ── WHAT LIVES HERE vs IN AssessmentResponseRepository ──────────────────
 *   Here  → anything that needs EntityManager / CriteriaBuilder
 *   There → upsertResponse (native MySQL upsert — no JPA equivalent),
 *            derived-name queries (findBy..., countBy... with no @Query),
 *            sumScoreByAssessmentId (plain Java default method)
 */
public interface AssessmentResponseRepositoryCustom {

    /**
     * Count only genuine vendor answers — excludes shell rows created by
     * saveReviewerEval for unanswered questions.
     *
     * WHY THIS EXISTS:
     * saveReviewerEval creates a stub AssessmentResponse row when the reviewer
     * evaluates a question the vendor never answered (so the verdict can be
     * persisted). These stub rows have responseText=NULL, selectedOptionInstanceId=NULL,
     * and scoreEarned=NULL. Counting them in percentComplete makes the assessment
     * show 100% completion even when many questions were never answered by the vendor.
     *
     * A row is a genuine vendor answer if ANY of these fields is non-null:
     *   responseText           (TEXT / NUMERIC / DATE answers)
     *   selectedOptionInstanceId (SINGLE_CHOICE / MULTI_CHOICE answers)
     *   scoreEarned            (any answered question that has a score assigned)
     *
     * FILE_UPLOAD questions are counted separately via documentLinkRepository
     * and added on top of this count — they never produce a response row.
     *
     * Used by: vendor GET, vendor list, org review GET, org list — all
     * endpoints that return percentComplete or answered count.
     */
    long countAnsweredByAssessmentId(Long assessmentId);

    /**
     * Bulk-update reviewer_status for all responses matching the given
     * (assessmentId, questionInstanceId) pair.
     * Replaces the former JPQL @Query UPDATE.
     */
    void updateResponderStatus(Long assessmentId, Long questionInstanceId, String status);

    /**
     * Count responses for a given assessment whose question belongs to
     * a specific section.  Requires joining AssessmentResponse ->
     * AssessmentQuestionInstance on questionInstanceId = id.
     * Replaces the former JPQL @Query with JOIN.
     */
    long countByAssessmentIdAndSectionInstanceId(Long assessmentId, Long sectionInstanceId);

    /**
     * Count responses that have been explicitly evaluated (reviewerStatus not
     * null and not PENDING) across a set of sections.
     * Returns 0 immediately when sectionInstanceIds is empty.
     */
    long countEvaluatedInSections(Long assessmentId, List<Long> sectionInstanceIds);

    /**
     * Count all question instances belonging to any of the supplied sections.
     * Returns 0 immediately when sectionInstanceIds is empty.
     */
    long countTotalInSections(List<Long> sectionInstanceIds);

    /**
     * Sum of scores after applying reviewer verdicts (PASS / PARTIAL / FAIL).
     *
     *   PASS    -> score_earned x 1.0   full credit
     *   PARTIAL -> score_earned x 0.5   half credit  (PARTIAL_SCORE_MULTIPLIER)
     *   FAIL    -> 0.0                  no credit
     *   PENDING / null -> score_earned  not yet reviewed; full credit preserved
     *
     * Returns 0.0 when no scored responses exist for the assessment.
     * Called at step 10 (consolidateScores), step 11 (assignRiskRating),
     * and final report generation. Pre-review paths (step 5 vendor submit)
     * continue to use the raw sumScoreEarnedByAssessmentId method.
     */
    Double sumReviewerAdjustedScoreByAssessmentId(Long assessmentId);

    /**
     * Raw SUM of scoreEarned — no reviewer verdict adjustment.
     *
     * Replaces the former Java default method in AssessmentResponseRepository:
     *   findByAssessmentId(id).stream().mapToDouble(...).sum()
     * That method loaded ALL response rows into Java heap just to sum one column.
     *
     * This declaration routes to a Criteria-API implementation in
     * AssessmentResponseRepositoryImpl — a single SQL SUM query.
     * Used at step 5 (vendor submit) before any reviewer has set verdicts.
     * Returns 0.0 when no scored responses exist.
     */
    Double sumScoreEarnedByAssessmentId(Long assessmentId);
}
