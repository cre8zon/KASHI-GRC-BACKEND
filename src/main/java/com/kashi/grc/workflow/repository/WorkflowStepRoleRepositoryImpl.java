package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;

/** JPA Criteria API implementation of WorkflowStepRoleRepositoryCustom. */
public class WorkflowStepRoleRepositoryImpl implements WorkflowStepRoleRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void deleteByStepId(Long stepId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<WorkflowStepRole> cd = cb.createCriteriaDelete(WorkflowStepRole.class);
        Root<WorkflowStepRole> r = cd.from(WorkflowStepRole.class);
        cd.where(cb.equal(r.get("stepId"), stepId));
        em.createQuery(cd).executeUpdate();
    }
}
