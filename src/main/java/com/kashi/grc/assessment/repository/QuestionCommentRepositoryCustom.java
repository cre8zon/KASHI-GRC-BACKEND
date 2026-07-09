package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.QuestionComment;
import java.util.List;

/** Criteria API fragment for QuestionCommentRepository. */
public interface QuestionCommentRepositoryCustom {

    /**
     * Comments on the LATEST response for an assessment+question.
     * The former JPQL used "IN (subquery ORDER BY id DESC LIMIT 1)"; JPA
     * subqueries can't express LIMIT, so this uses the equivalent
     * responseId = (SELECT MAX(r.id) ...) — identical result.
     */
    List<QuestionComment> findByAssessmentAndQuestion(Long assessmentId, Long questionInstanceId);
}
