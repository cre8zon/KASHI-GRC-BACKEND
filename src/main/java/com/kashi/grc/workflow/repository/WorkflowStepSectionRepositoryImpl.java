package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepSection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;

/** JPA Criteria API implementation of WorkflowStepSectionRepositoryCustom. */
public class WorkflowStepSectionRepositoryImpl implements WorkflowStepSectionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void deleteByStepId(Long stepId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<WorkflowStepSection> cd = cb.createCriteriaDelete(WorkflowStepSection.class);
        Root<WorkflowStepSection> s = cd.from(WorkflowStepSection.class);
        cd.where(cb.equal(s.get("stepId"), stepId));
        em.createQuery(cd).executeUpdate();
    }
}
