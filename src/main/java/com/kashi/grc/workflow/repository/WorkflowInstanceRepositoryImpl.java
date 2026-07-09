package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;
import java.util.Optional;

/** JPA Criteria API implementation of WorkflowInstanceRepositoryCustom. */
public class WorkflowInstanceRepositoryImpl implements WorkflowInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<WorkflowInstance> findActiveByEntityTypeAndEntityId(String entityType, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<WorkflowInstance> cq = cb.createQuery(WorkflowInstance.class);
        Root<WorkflowInstance> wi = cq.from(WorkflowInstance.class);
        cq.where(
                cb.equal(wi.get("entityType"), entityType),
                cb.equal(wi.get("entityId"), entityId),
                cb.equal(wi.get("status"), WorkflowStatus.IN_PROGRESS)
        );
        cq.orderBy(cb.desc(wi.get("createdAt")));

        List<WorkflowInstance> result = em.createQuery(cq).setMaxResults(1).getResultList();
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}
