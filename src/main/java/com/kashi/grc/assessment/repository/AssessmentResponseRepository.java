package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.Modifying;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AssessmentResponseRepository extends JpaRepository<AssessmentResponse, Long> {

    /**
     * Atomic upsert using MySQL INSERT ... ON DUPLICATE KEY UPDATE.
     *
     * WHY: The try-catch approach fails inside @Transactional — when save() throws
     * DataIntegrityViolationException, Hibernate marks the session "rollback-only"
     * and any subsequent query (like findFirstBy... in the catch) triggers a flush
     * of the broken entity (null id) → AssertionFailure: null identifier.
     *
     * This native query never throws on a duplicate — the DB handles the conflict
     * atomically. The Hibernate session stays clean.
     *
     * clearAutomatically=true evicts the cached entity so the next findFirstBy...
     * reads fresh data from DB instead of stale Hibernate L1 cache.
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
                    "  response_text = VALUES(response_text), " +
                    "  selected_option_instance_id = VALUES(selected_option_instance_id), " +
                    "  score_earned = VALUES(score_earned), " +
                    "  submitted_by = VALUES(submitted_by), " +
                    "  submitted_at = VALUES(submitted_at), " +
                    "  updated_at = NOW(6)")
    void upsertResponse(
            @Param("tenantId")                  Long tenantId,
            @Param("assessmentId")              Long assessmentId,
            @Param("questionInstanceId")        Long questionInstanceId,
            @Param("responseText")              String responseText,
            @Param("selectedOptionInstanceId")  Long selectedOptionInstanceId,
            @Param("scoreEarned")               Double scoreEarned,
            @Param("submittedBy")               Long submittedBy,
            @Param("submittedAt")               LocalDateTime submittedAt);
    List<AssessmentResponse> findByAssessmentId(Long assessmentId);
    // findFirst tolerates duplicate rows (same assessmentId+questionInstanceId saved twice on retry/double-submit)
    // Always returns the latest row (highest id) so we never crash with NonUniqueResultException
    Optional<AssessmentResponse> findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(Long assessmentId, Long questionInstanceId);
    long countByAssessmentId(Long assessmentId);

    /**
     * Update reviewer_status for a specific (assessmentId, questionInstanceId) pair.
     * Used by accept-contributor, override-answer, request-contributor-revision endpoints
     * on the vendor fill side to stamp ACCEPTED / OVERRIDDEN / REVISION_REQUESTED
     * without triggering the full upsertResponse path.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AssessmentResponse r SET r.reviewerStatus = :status " +
            "WHERE r.assessmentId = :assessmentId AND r.questionInstanceId = :questionInstanceId")
    void updateResponderStatus(
            @Param("assessmentId")       Long assessmentId,
            @Param("questionInstanceId") Long questionInstanceId,
            @Param("status")             String status);

    @Query("SELECT COUNT(r) FROM AssessmentResponse r " +
            "JOIN AssessmentQuestionInstance q ON q.id = r.questionInstanceId " +
            "WHERE r.assessmentId = :assessmentId AND q.sectionInstanceId = :sectionInstanceId")
    long countByAssessmentIdAndSectionInstanceId(
            @Param("assessmentId") Long assessmentId,
            @Param("sectionInstanceId") Long sectionInstanceId);

    /**
     * Count questions across a set of sections that have been evaluated
     * (reviewerStatus is not null and not PENDING).
     * Used by saveReviewerEval to detect when SCORE_ANSWERS section is complete.
     */
    @Query("SELECT COUNT(r) FROM AssessmentResponse r " +
            "JOIN AssessmentQuestionInstance q ON q.id = r.questionInstanceId " +
            "WHERE r.assessmentId = :assessmentId " +
            "AND q.sectionInstanceId IN :sectionInstanceIds " +
            "AND r.reviewerStatus IS NOT NULL " +
            "AND r.reviewerStatus <> 'PENDING'")
    long countEvaluatedInSections(
            @Param("assessmentId") Long assessmentId,
            @Param("sectionInstanceIds") List<Long> sectionInstanceIds);

    /**
     * Count total questions across a set of sections.
     * Used alongside countEvaluatedInSections to determine completion percentage.
     */
    @Query("SELECT COUNT(q) FROM AssessmentQuestionInstance q " +
            "WHERE q.sectionInstanceId IN :sectionInstanceIds")
    long countTotalInSections(
            @Param("sectionInstanceIds") List<Long> sectionInstanceIds);

    default Double sumScoreByAssessmentId(Long assessmentId) {
        return findByAssessmentId(assessmentId).stream()
                .filter(r -> r.getScoreEarned() != null)
                .mapToDouble(r -> r.getScoreEarned())
                .sum();
    }
}