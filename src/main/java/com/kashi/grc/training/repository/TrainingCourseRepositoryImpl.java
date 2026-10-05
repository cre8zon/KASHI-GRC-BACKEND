package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingCourse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class TrainingCourseRepositoryImpl implements TrainingCourseRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long nextCourseRefSequence(Long tenantId) {
        LocalDateTime startOfYear     = LocalDate.now().withDayOfYear(1).atStartOfDay();
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<TrainingCourse> c = cq.from(TrainingCourse.class);

        // equal() on a nullable column never matches NULL, so a platform course
        // (tenantId NULL) needs isNull() rather than equal(null).
        Predicate scope = tenantId == null
                ? cb.isNull(c.get("tenantId"))
                : cb.equal(c.get("tenantId"), tenantId);

        cq.select(cb.count(c)).where(scope,
                cb.greaterThanOrEqualTo(c.<LocalDateTime>get("createdAt"), startOfYear),
                cb.lessThan(c.<LocalDateTime>get("createdAt"), startOfNextYear));
        Long count = em.createQuery(cq).getSingleResult();
        return (count != null ? count : 0L) + 1;
    }
}
