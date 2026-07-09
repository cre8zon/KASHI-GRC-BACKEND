package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.Collection;
import java.util.List;

/**
 * JPA Criteria API implementation of TaskInstanceRepositoryCustom.
 *
 * The former JPQL used an ad-hoc "JOIN StepInstance s ON s.id = t.stepInstanceId"
 * (no mapped association). In Criteria the same link is expressed as an
 * IN-subquery on StepInstance IDs — identical result set for these predicates.
 */
public class TaskInstanceRepositoryImpl implements TaskInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    /** Subquery: step instance IDs belonging to a workflow instance. */
    private Subquery<Long> stepIdsOfInstance(AbstractQuery<?> cq, CriteriaBuilder cb,
                                             Long workflowInstanceId) {
        Subquery<Long> sub = cq.subquery(Long.class);
        Root<StepInstance> s = sub.from(StepInstance.class);
        sub.select(s.get("id"))
           .where(cb.equal(s.get("workflowInstanceId"), workflowInstanceId));
        return sub;
    }

    @Override
    public boolean existsByUserIdAndWorkflowInstanceIdAndStatusIn(
            Long userId, Long workflowInstanceId, Collection<TaskStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return false;

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<TaskInstance> t = cq.from(TaskInstance.class);
        cq.select(cb.count(t)).where(
                cb.equal(t.get("assignedUserId"), userId),
                t.get("stepInstanceId").in(stepIdsOfInstance(cq, cb, workflowInstanceId)),
                t.get("status").in(statuses)
        );
        Long count = em.createQuery(cq).getSingleResult();
        return count != null && count > 0;
    }

    @Override
    public boolean existsByUserIdAndWorkflowInstanceId(Long userId, Long workflowInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<TaskInstance> t = cq.from(TaskInstance.class);
        cq.select(cb.count(t)).where(
                cb.equal(t.get("assignedUserId"), userId),
                t.get("stepInstanceId").in(stepIdsOfInstance(cq, cb, workflowInstanceId))
        );
        Long count = em.createQuery(cq).getSingleResult();
        return count != null && count > 0;
    }

    @Override
    public List<TaskInstance> findActorTasksForInstance(Long workflowInstanceId, Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TaskInstance> cq = cb.createQuery(TaskInstance.class);
        Root<TaskInstance> t = cq.from(TaskInstance.class);
        cq.where(
                t.get("stepInstanceId").in(stepIdsOfInstance(cq, cb, workflowInstanceId)),
                cb.equal(t.get("assignedUserId"), userId),
                cb.equal(t.get("taskRole"), TaskRole.ACTOR),
                cb.equal(t.get("status"), TaskStatus.APPROVED)
        );
        return em.createQuery(cq).getResultList();
    }
}
