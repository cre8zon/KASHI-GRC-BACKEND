package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskSectionAssignment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

/** JPA Criteria API implementation of TaskSectionAssignmentRepositoryCustom. */
public class TaskSectionAssignmentRepositoryImpl implements TaskSectionAssignmentRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long countIncomplete(Long taskInstanceId, String sectionKey) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<TaskSectionAssignment> a = cq.from(TaskSectionAssignment.class);
        cq.select(cb.count(a)).where(
                cb.equal(a.get("taskInstanceId"), taskInstanceId),
                cb.equal(a.get("sectionKey"), sectionKey),
                cb.notEqual(a.get("status"), "COMPLETED")
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }
}
