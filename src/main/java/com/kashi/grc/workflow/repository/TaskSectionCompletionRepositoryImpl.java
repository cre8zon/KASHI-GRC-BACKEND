package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskSectionCompletion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of TaskSectionCompletionRepositoryCustom. */
public class TaskSectionCompletionRepositoryImpl implements TaskSectionCompletionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long countCompletedRequired(Long taskInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<TaskSectionCompletion> c = cq.from(TaskSectionCompletion.class);
        cq.select(cb.count(c)).where(
                cb.equal(c.get("taskInstanceId"), taskInstanceId),
                cb.isTrue(c.get("snapRequired")),
                cb.isTrue(c.get("completed"))
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public long countTotalRequired(Long taskInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<TaskSectionCompletion> c = cq.from(TaskSectionCompletion.class);
        cq.select(cb.count(c)).where(
                cb.equal(c.get("taskInstanceId"), taskInstanceId),
                cb.isTrue(c.get("snapRequired"))
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<TaskSectionCompletion> findIncompleteRequired(Long taskInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TaskSectionCompletion> cq = cb.createQuery(TaskSectionCompletion.class);
        Root<TaskSectionCompletion> c = cq.from(TaskSectionCompletion.class);
        cq.where(
                cb.equal(c.get("taskInstanceId"), taskInstanceId),
                cb.isTrue(c.get("snapRequired")),
                cb.isFalse(c.get("completed"))
        );
        return em.createQuery(cq).getResultList();
    }
}
