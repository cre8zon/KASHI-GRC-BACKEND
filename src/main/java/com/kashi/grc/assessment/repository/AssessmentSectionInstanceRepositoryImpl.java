package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentSectionInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AssessmentSectionInstanceRepositoryCustom. */
public class AssessmentSectionInstanceRepositoryImpl
        implements AssessmentSectionInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    private List<Long> distinctAssigned(String field, Long templateInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AssessmentSectionInstance> s = cq.from(AssessmentSectionInstance.class);
        cq.select(s.get(field)).distinct(true).where(
                cb.equal(s.get("templateInstanceId"), templateInstanceId),
                cb.isNotNull(s.get(field))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Long> findDistinctAssignedResponderIds(Long templateInstanceId) {
        return distinctAssigned("assignedUserId", templateInstanceId);
    }

    @Override
    public List<Long> findDistinctAssignedReviewerIds(Long templateInstanceId) {
        return distinctAssigned("reviewerAssignedUserId", templateInstanceId);
    }
}
