package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA Criteria API implementation of StepInstanceRepositoryCustom.
 *
 * Conversion notes:
 *  - findByIdForUpdate keeps the pessimistic lock via
 *    TypedQuery.setLockMode(PESSIMISTIC_WRITE) — same SELECT ... FOR UPDATE
 *    the former @Lock/@Query pair produced.
 *  - findStuckSteps' NOT EXISTS correlated subquery → cb.not(cb.exists(sub)).
 *  - Enum string literals → StepStatus / TaskRole / TaskStatus constants.
 */
public class StepInstanceRepositoryImpl implements StepInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<StepInstance> findByIdForUpdate(Long id) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<StepInstance> cq = cb.createQuery(StepInstance.class);
        Root<StepInstance> s = cq.from(StepInstance.class);
        cq.where(cb.equal(s.get("id"), id));

        List<StepInstance> result = em.createQuery(cq)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public List<StepInstance> findAllSlaBreached(LocalDateTime now) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<StepInstance> cq = cb.createQuery(StepInstance.class);
        Root<StepInstance> si = cq.from(StepInstance.class);
        cq.where(
                cb.equal(si.get("status"), StepStatus.IN_PROGRESS),
                cb.isNotNull(si.get("slaDueAt")),
                cb.lessThan(si.get("slaDueAt"), now),
                cb.isNull(si.get("completedAt"))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<StepInstance> findStuckSteps() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<StepInstance> cq = cb.createQuery(StepInstance.class);
        Root<StepInstance> si = cq.from(StepInstance.class);

        // NOT EXISTS (SELECT 1 FROM TaskInstance ti WHERE ti.stepInstanceId = si.id
        //             AND ti.taskRole = ACTOR AND ti.status IN (PENDING, APPROVED))
        Subquery<Long> sub = cq.subquery(Long.class);
        Root<TaskInstance> ti = sub.from(TaskInstance.class);
        sub.select(cb.literal(1L)).where(
                cb.equal(ti.get("stepInstanceId"), si.get("id")),
                cb.equal(ti.get("taskRole"), TaskRole.ACTOR),
                ti.get("status").in(List.of(TaskStatus.PENDING, TaskStatus.APPROVED))
        );

        cq.where(
                cb.equal(si.get("status"), StepStatus.IN_PROGRESS),
                cb.not(cb.exists(sub))
        );
        return em.createQuery(cq).getResultList();
    }
}
