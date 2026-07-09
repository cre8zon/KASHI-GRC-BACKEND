package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentResponse;
import com.kashi.grc.assessment.domain.QuestionComment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of QuestionCommentRepositoryCustom. */
public class QuestionCommentRepositoryImpl implements QuestionCommentRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<QuestionComment> findByAssessmentAndQuestion(Long assessmentId, Long questionInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<QuestionComment> cq = cb.createQuery(QuestionComment.class);
        Root<QuestionComment> c = cq.from(QuestionComment.class);

        // SELECT MAX(r.id) FROM AssessmentResponse r WHERE assessment + question
        Subquery<Long> latestResponse = cq.subquery(Long.class);
        Root<AssessmentResponse> r = latestResponse.from(AssessmentResponse.class);
        latestResponse.select(cb.max(r.get("id"))).where(
                cb.equal(r.get("assessmentId"), assessmentId),
                cb.equal(r.get("questionInstanceId"), questionInstanceId)
        );

        cq.where(cb.equal(c.get("responseId"), latestResponse));
        cq.orderBy(cb.asc(c.get("createdAt")));
        return em.createQuery(cq).getResultList();
    }
}
