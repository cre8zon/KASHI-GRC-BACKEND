package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

/**
 * JPA Criteria API implementation of ContributorSectionSubmissionRepositoryCustom.
 * Counts over AssessmentQuestionInstance (not the submission entity) —
 * exactly what the former JPQL did.
 */
public class ContributorSectionSubmissionRepositoryImpl implements ContributorSectionSubmissionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long countDistinctSectionsWithAssignments(Long assessmentId, Long contributorUserId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AssessmentQuestionInstance> q = cq.from(AssessmentQuestionInstance.class);
        cq.select(cb.countDistinct(q.get("sectionInstanceId"))).where(
                cb.equal(q.get("assessmentId"), assessmentId),
                cb.equal(q.get("assignedUserId"), contributorUserId)
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }
}
