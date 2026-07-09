package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepAssignerRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;

/** JPA Criteria API implementation of WorkflowStepAssignerRoleRepositoryCustom. */
public class WorkflowStepAssignerRoleRepositoryImpl implements WorkflowStepAssignerRoleRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void deleteByStepId(Long stepId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<WorkflowStepAssignerRole> cd = cb.createCriteriaDelete(WorkflowStepAssignerRole.class);
        Root<WorkflowStepAssignerRole> r = cd.from(WorkflowStepAssignerRole.class);
        cd.where(cb.equal(r.get("stepId"), stepId));
        em.createQuery(cd).executeUpdate();
    }
}
