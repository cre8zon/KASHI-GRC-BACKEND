package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.QuestionComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface QuestionCommentRepository extends JpaRepository<QuestionComment, Long> {
    List<QuestionComment> findByResponseIdOrderByCreatedAt(Long responseId);
    List<QuestionComment> findByResponseIdIn(List<Long> responseIds);

    /**
     * Fetch all comments for a question, joining via the latest response row.
     * Used by the per-question discussion thread on vendor fill and org review pages.
     * Returns USER_COMMENT rows (discussion) ordered oldest-first.
     */
    @Query("SELECT c FROM QuestionComment c " +
            "WHERE c.responseId IN (" +
            "  SELECT r.id FROM AssessmentResponse r " +
            "  WHERE r.assessmentId = :assessmentId AND r.questionInstanceId = :questionInstanceId " +
            "  ORDER BY r.id DESC LIMIT 1" +
            ") ORDER BY c.createdAt ASC")
    List<QuestionComment> findByAssessmentAndQuestion(
            @Param("assessmentId")       Long assessmentId,
            @Param("questionInstanceId") Long questionInstanceId);
}
