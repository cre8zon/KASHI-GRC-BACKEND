package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepObserverRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;

/** JPA Criteria API implementation of WorkflowStepObserverRoleRepositoryCustom. */
public class WorkflowStepObserverRoleRepositoryImpl implements WorkflowStepObserverRoleRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void deleteByStepId(Long stepId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<WorkflowStepObserverRole> cd = cb.createCriteriaDelete(WorkflowStepObserverRole.class);
        Root<WorkflowStepObserverRole> r = cd.from(WorkflowStepObserverRole.class);
        cd.where(cb.equal(r.get("stepId"), stepId));
        em.createQuery(cd).executeUpdate();
    }
}
