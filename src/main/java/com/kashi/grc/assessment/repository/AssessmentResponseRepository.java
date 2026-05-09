package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AssessmentResponseRepository
        extends JpaRepository<AssessmentResponse, Long>,
        AssessmentResponseRepositoryCustom {

    /**
     * Atomic upsert using MySQL INSERT ... ON DUPLICATE KEY UPDATE.
     *
     * This is the one intentional native query in the project.
     *
     * WHY NATIVE: MySQL's ON DUPLICATE KEY UPDATE is not part of the JPA
     * specification — CriteriaBuilder has no upsert concept. The standard
     * JPA alternative (save() inside @Transactional) fails here: when save()
     * throws DataIntegrityViolationException on a duplicate key, Hibernate
     * marks the session "rollback-only". Any subsequent query in the same
     * transaction then triggers a flush of the broken entity (id = null)
     * → AssertionFailure: null identifier. The native upsert is atomic at
     * the DB level — no exception, no session poisoning, no race condition.
     *
     * clearAutomatically = true evicts the cached entity so the next
     * findFirstBy... call reads fresh data from DB instead of stale L1 cache.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value =
            "INSERT INTO assessment_responses " +
                    "  (tenant_id, assessment_id, question_instance_id, response_text, " +
                    "   selected_option_instance_id, score_earned, submitted_by, submitted_at, " +
                    "   reviewer_status, created_at, updated_at) " +
                    "VALUES " +
                    "  (:tenantId, :assessmentId, :questionInstanceId, :responseText, " +
                    "   :selectedOptionInstanceId, :scoreEarned, :submittedBy, :submittedAt, " +
                    "   'PENDING', NOW(6), NOW(6)) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "  response_text                = VALUES(response_text), " +
                    "  selected_option_instance_id  = VALUES(selected_option_instance_id), " +
                    "  score_earned                 = VALUES(score_earned), " +
                    "  submitted_by                 = VALUES(submitted_by), " +
                    "  submitted_at                 = VALUES(submitted_at), " +
                    "  updated_at                   = NOW(6)")
    void upsertResponse(
            @Param("tenantId")                 Long tenantId,
            @Param("assessmentId")             Long assessmentId,
            @Param("questionInstanceId")       Long questionInstanceId,
            @Param("responseText")             String responseText,
            @Param("selectedOptionInstanceId") Long selectedOptionInstanceId,
            @Param("scoreEarned")              Double scoreEarned,
            @Param("submittedBy")              Long submittedBy,
            @Param("submittedAt")              LocalDateTime submittedAt);

    // ── Spring Data derived queries (no @Query needed) ────────────────────

    List<AssessmentResponse> findByAssessmentId(Long assessmentId);

    /**
     * findFirst tolerates duplicate rows (same assessmentId + questionInstanceId
     * saved twice on retry / double-submit). Always returns the latest row
     * (highest id) so we never crash with NonUniqueResultException.
     */
    Optional<AssessmentResponse> findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(
            Long assessmentId, Long questionInstanceId);

    long countByAssessmentId(Long assessmentId);

    // ── Java-level aggregate (no query annotation needed) ─────────────────

    /**
     * Raw sum of scoreEarned — used at step 5 (vendor submit) before any
     * reviewer has set verdicts. All reviewerStatus values are PENDING at
     * that point, so both this and sumReviewerAdjustedScoreByAssessmentId
     * would return the same number, but this is semantically correct for the
     * pre-review phase.
     */
    default Double sumScoreByAssessmentId(Long assessmentId) {
        return findByAssessmentId(assessmentId).stream()
                .filter(r -> r.getScoreEarned() != null)
                .mapToDouble(AssessmentResponse::getScoreEarned)
                .sum();
    }
}