package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.AssessmentResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of AssessmentResponseRepositoryCustom.
 *
 * Spring Data discovers this class automatically by the "Impl" suffix on
 * AssessmentResponseRepository — no @Bean or @Configuration required.
 *
 * Every method uses CriteriaBuilder so all queries are:
 *   - Type-safe (compile-time field names via root.get("fieldName"))
 *   - DB-agnostic (no SQL dialect in this file)
 *   - Single round-trips (aggregation done at DB level, not in Java)
 *
 * ── CROSS-ENTITY JOINS ────────────────────────────────────────────────────
 * AssessmentResponse has no @ManyToOne mapping to AssessmentQuestionInstance
 * — the relationship is stored as a plain Long (questionInstanceId).
 * Criteria API handles this via two Root declarations and a manual join
 * predicate: cb.equal(r.get("questionInstanceId"), q.get("id")).
 * This generates an equivalent INNER JOIN at the SQL level.
 */
public class AssessmentResponseRepositoryImpl
        implements AssessmentResponseRepositoryCustom {

    private static final double PARTIAL_SCORE_MULTIPLIER = 0.5;

    @PersistenceContext
    private EntityManager em;

    // ── 1. updateResponderStatus ──────────────────────────────────────────

    /**
     * Count only rows where the vendor actually submitted an answer.
     *
     * saveReviewerEval creates stub rows (responseText=NULL,
     * selectedOptionInstanceId=NULL, scoreEarned=NULL) for questions the vendor
     * never answered so the reviewer verdict can be stored. Those stubs must
     * NOT count as "answered" for the completion % — otherwise every question
     * the reviewer auto-FAILs shows up as answered and completion hits 100%
     * even when the vendor answered nothing.
     *
     * A row is genuine if ANY of these fields is non-null:
     *   responseText           → TEXT / NUMERIC / DATE answers
     *   selectedOptionInstanceId → SINGLE_CHOICE / MULTI_CHOICE answers
     *   scoreEarned            → any scored answer
     *
     * FILE_UPLOAD questions are handled separately (via DocumentLink) and
     * added on top of this count by the caller — they never produce a row.
     */
    @Override
    public long countAnsweredByAssessmentId(Long assessmentId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AssessmentResponse> root = cq.from(AssessmentResponse.class);

        Predicate hasText   = cb.isNotNull(root.get("responseText"));
        Predicate hasOption = cb.isNotNull(root.get("selectedOptionInstanceId"));
        Predicate hasScore  = cb.isNotNull(root.get("scoreEarned"));

        cq.select(cb.count(root))
                .where(
                        cb.equal(root.get("assessmentId"), assessmentId),
                        cb.or(hasText, hasOption, hasScore)
                );

        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }

    // ── 2. updateResponderStatus ──────────────────────────────────────────────

    @Override
    public void updateResponderStatus(Long assessmentId, Long questionInstanceId, String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<AssessmentResponse> cu = cb.createCriteriaUpdate(AssessmentResponse.class);
        Root<AssessmentResponse> root = cu.from(AssessmentResponse.class);

        cu.set(root.get("reviewerStatus"), status)
                .where(
                        cb.equal(root.get("assessmentId"),      assessmentId),
                        cb.equal(root.get("questionInstanceId"), questionInstanceId)
                );

        em.createQuery(cu).executeUpdate();
    }

    // ── 2. countByAssessmentIdAndSectionInstanceId ────────────────────────

    /**
     * COUNT with cross-entity join via two Root declarations.
     *
     * Equivalent JPQL (removed):
     *   SELECT COUNT(r) FROM AssessmentResponse r
     *   JOIN AssessmentQuestionInstance q ON q.id = r.questionInstanceId
     *   WHERE r.assessmentId = :assessmentId
     *   AND   q.sectionInstanceId = :sectionInstanceId
     *
     * Two from() calls produce a CROSS JOIN; the first predicate
     * (r.questionInstanceId = q.id) converts it to an INNER JOIN.
     */
    @Override
    public long countByAssessmentIdAndSectionInstanceId(Long assessmentId, Long sectionInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AssessmentResponse>         r = cq.from(AssessmentResponse.class);
        Root<AssessmentQuestionInstance> q = cq.from(AssessmentQuestionInstance.class);

        cq.select(cb.count(r))
                .where(
                        cb.equal(r.get("questionInstanceId"), q.get("id")),
                        cb.equal(r.get("assessmentId"),        assessmentId),
                        cb.equal(q.get("sectionInstanceId"),   sectionInstanceId)
                );

        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }

    // ── 3. countEvaluatedInSections ───────────────────────────────────────

    /**
     * COUNT with cross-entity join, IN predicate, and IS NOT NULL check.
     *
     * Equivalent JPQL (removed):
     *   SELECT COUNT(r) FROM AssessmentResponse r
     *   JOIN AssessmentQuestionInstance q ON q.id = r.questionInstanceId
     *   WHERE r.assessmentId        = :assessmentId
     *   AND   q.sectionInstanceId  IN :sectionInstanceIds
     *   AND   r.reviewerStatus     IS NOT NULL
     *   AND   r.reviewerStatus     <> 'PENDING'
     *
     * Guard: returns 0 immediately for an empty section list —
     * passing an empty collection to .in() produces invalid SQL.
     */
    @Override
    public long countEvaluatedInSections(Long assessmentId, List<Long> sectionInstanceIds) {
        if (sectionInstanceIds == null || sectionInstanceIds.isEmpty()) return 0L;

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AssessmentResponse>         r = cq.from(AssessmentResponse.class);
        Root<AssessmentQuestionInstance> q = cq.from(AssessmentQuestionInstance.class);

        cq.select(cb.count(r))
                .where(
                        cb.equal(r.get("questionInstanceId"), q.get("id")),
                        cb.equal(r.get("assessmentId"),        assessmentId),
                        q.get("sectionInstanceId").in(sectionInstanceIds),
                        cb.isNotNull(r.get("reviewerStatus")),
                        cb.notEqual(r.get("reviewerStatus"), "PENDING")
                );

        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }

    // ── 4. countTotalInSections ───────────────────────────────────────────

    /**
     * COUNT directly on AssessmentQuestionInstance (no response join needed).
     *
     * Equivalent JPQL (removed):
     *   SELECT COUNT(q) FROM AssessmentQuestionInstance q
     *   WHERE q.sectionInstanceId IN :sectionInstanceIds
     *
     * Guard: returns 0 immediately for an empty section list.
     */
    @Override
    public long countTotalInSections(List<Long> sectionInstanceIds) {
        if (sectionInstanceIds == null || sectionInstanceIds.isEmpty()) return 0L;

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AssessmentQuestionInstance> q = cq.from(AssessmentQuestionInstance.class);

        cq.select(cb.count(q))
                .where(q.get("sectionInstanceId").in(sectionInstanceIds));

        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }

    // ── 5. sumReviewerAdjustedScoreByAssessmentId ─────────────────────────

    /**
     * Conditional SUM using CriteriaBuilder.selectCase().
     *
     *   CASE WHEN reviewer_status = 'FAIL'    THEN 0.0
     *        WHEN reviewer_status = 'PARTIAL' THEN score_earned * 0.5
     *        ELSE score_earned   -- PASS, PENDING, null -> full credit
     *   END
     *
     * Single DB round-trip regardless of question count.
     * Returns 0.0 when no scored responses exist.
     */
    @Override
    public Double sumReviewerAdjustedScoreByAssessmentId(Long assessmentId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Double> cq = cb.createQuery(Double.class);
        Root<AssessmentResponse> root = cq.from(AssessmentResponse.class);

        Expression<Double> adjustedScore = cb.<Double>selectCase()
                .when(
                        cb.equal(root.<String>get("reviewerStatus"), "FAIL"),
                        cb.literal(0.0)
                )
                .when(
                        cb.equal(root.<String>get("reviewerStatus"), "PARTIAL"),
                        cb.prod(
                                root.<Double>get("scoreEarned"),
                                cb.literal(PARTIAL_SCORE_MULTIPLIER)
                        )
                )
                .otherwise(root.<Double>get("scoreEarned"));

        cq.select(cb.sum(adjustedScore))
                .where(
                        cb.equal(root.get("assessmentId"), assessmentId),
                        cb.isNotNull(root.get("scoreEarned"))
                );

        Double result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0.0;
    }

    // ── 6. sumScoreEarnedByAssessmentId ──────────────────────────────────────

    /**
     * Raw SUM of scoreEarned — no reviewer verdict adjustment.
     *
     * Replaces the former Java default method in AssessmentResponseRepository:
     *   findByAssessmentId(id).stream()
     *           .filter(r -> r.getScoreEarned() != null)
     *           .mapToDouble(AssessmentResponse::getScoreEarned).sum()
     *
     * That method loaded ALL response rows into Java heap just to aggregate
     * one double column. This implementation does it in a single SQL SUM query.
     *
     * Used at step 5 (vendor submit) before any reviewer has set verdicts.
     * Returns 0.0 when no scored responses exist for the assessment.
     */
    @Override
    public Double sumScoreEarnedByAssessmentId(Long assessmentId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Double> cq = cb.createQuery(Double.class);
        Root<AssessmentResponse> root = cq.from(AssessmentResponse.class);

        cq.select(cb.sum(root.<Double>get("scoreEarned")))
                .where(
                        cb.equal(root.get("assessmentId"), assessmentId),
                        cb.isNotNull(root.get("scoreEarned"))
                );

        Double result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0.0;
    }
}
