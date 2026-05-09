package com.kashi.grc.assessment.repository;

import java.util.List;

/**
 * Custom repository extension for AssessmentResponseRepository.
 *
 * Declares every operation that requires JPA Criteria API — either because
 * it involves conditional aggregation, cross-entity joins without a mapped
 * relationship, or bulk updates.
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
     * Count responses for a given assessment whose question belongs to
     * any of the supplied sections AND whose reviewerStatus has been
     * explicitly set (not null, not PENDING).
     * Returns 0 immediately when sectionInstanceIds is empty.
     * Replaces the former JPQL @Query with JOIN + IN + IS NOT NULL.
     */
    long countEvaluatedInSections(Long assessmentId, List<Long> sectionInstanceIds);

    /**
     * Count all question instances belonging to any of the supplied sections.
     * Queries AssessmentQuestionInstance directly (no AssessmentResponse join).
     * Returns 0 immediately when sectionInstanceIds is empty.
     * Replaces the former JPQL @Query on AssessmentQuestionInstance.
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
     * continue to use the raw sumScoreByAssessmentId default method.
     */
    Double sumReviewerAdjustedScoreByAssessmentId(Long assessmentId);
}