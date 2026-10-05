package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingAssignment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.List;

public class TrainingAssignmentRepositoryImpl implements TrainingAssignmentRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Object[]> countByStatusForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<TrainingAssignment> a = cq.from(TrainingAssignment.class);
        Path<?> g = a.get("status");
        cq.multiselect(g, cb.count(a))
          .where(cb.equal(a.get("tenantId"), tenantId), cb.isFalse(a.get("isDeleted")))
          .groupBy(g);
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<TrainingAssignment> findOverdue(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TrainingAssignment> cq = cb.createQuery(TrainingAssignment.class);
        Root<TrainingAssignment> a = cq.from(TrainingAssignment.class);
        cq.where(
                cb.equal(a.get("tenantId"), tenantId),
                cb.isFalse(a.get("isDeleted")),
                cb.isNotNull(a.get("dueAt")),
                cb.lessThan(a.<LocalDateTime>get("dueAt"), LocalDateTime.now()),
                a.get("status").in(TrainingAssignment.Status.ASSIGNED,
                                   TrainingAssignment.Status.IN_PROGRESS));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public Integer maxVersionFor(Long personnelId, TrainingAssignment.TargetType targetType, Long targetId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Integer> cq = cb.createQuery(Integer.class);
        Root<TrainingAssignment> a = cq.from(TrainingAssignment.class);
        cq.select(cb.max(a.get("targetVersion"))).where(
                cb.equal(a.get("personnelId"), personnelId),
                cb.equal(a.get("targetType"), targetType),
                cb.equal(a.get("targetId"), targetId));
        return em.createQuery(cq).getSingleResult();
    }
}
