package com.kashi.grc.incident.repository;

import com.kashi.grc.incident.domain.Incident;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class IncidentRepositoryImpl implements IncidentRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long nextIncidentRefSequence(Long tenantId) {
        LocalDateTime startOfYear     = LocalDate.now().withDayOfYear(1).atStartOfDay();
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Incident> i = cq.from(Incident.class);
        cq.select(cb.count(i)).where(
                cb.equal(i.get("tenantId"), tenantId),
                cb.greaterThanOrEqualTo(i.<LocalDateTime>get("createdAt"), startOfYear),
                cb.lessThan(i.<LocalDateTime>get("createdAt"), startOfNextYear));
        Long count = em.createQuery(cq).getSingleResult();
        return (count != null ? count : 0L) + 1;
    }

    @Override public List<Object[]> countByStatusForTenant(Long t)   { return grouped("status", t); }
    @Override public List<Object[]> countBySeverityForTenant(Long t) { return grouped("severity", t); }
    @Override public List<Object[]> countByTypeForTenant(Long t)     { return grouped("incidentType", t); }

    private List<Object[]> grouped(String attribute, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Incident> i = cq.from(Incident.class);
        Path<?> g = i.get(attribute);
        cq.multiselect(g, cb.count(i))
          .where(cb.equal(i.get("tenantId"), tenantId), cb.isFalse(i.get("isDeleted")))
          .groupBy(g);
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Incident> findSlaBreached(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Incident> cq = cb.createQuery(Incident.class);
        Root<Incident> i = cq.from(Incident.class);
        cq.where(
                cb.equal(i.get("tenantId"), tenantId),
                cb.isFalse(i.get("isDeleted")),
                cb.isNotNull(i.get("resolutionDueAt")),
                cb.lessThan(i.<LocalDateTime>get("resolutionDueAt"), LocalDateTime.now()),
                cb.not(i.get("status").in(Incident.Status.CLOSED, Incident.Status.FALSE_POSITIVE)));
        return em.createQuery(cq).getResultList();
    }

    /**
     * Averaged in Java rather than in SQL because TIMESTAMPDIFF is not portable
     * through the Criteria API without a vendor function, and the closed-incident
     * set is small by nature. If it ever is not, this becomes a native query.
     */
    @Override
    public Double[] meanTimesInHours(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Incident> cq = cb.createQuery(Incident.class);
        Root<Incident> i = cq.from(Incident.class);
        cq.where(
                cb.equal(i.get("tenantId"), tenantId),
                cb.isFalse(i.get("isDeleted")),
                cb.equal(i.get("status"), Incident.Status.CLOSED));
        List<Incident> closed = em.createQuery(cq).getResultList();

        double detectSum = 0, containSum = 0;
        int detectN = 0, containN = 0;
        for (Incident inc : closed) {
            if (inc.getOccurredAt() != null && inc.getDetectedAt() != null
                    && !inc.getDetectedAt().isBefore(inc.getOccurredAt())) {
                detectSum += Duration.between(inc.getOccurredAt(), inc.getDetectedAt()).toMinutes() / 60.0;
                detectN++;
            }
            if (inc.getDetectedAt() != null && inc.getContainedAt() != null
                    && !inc.getContainedAt().isBefore(inc.getDetectedAt())) {
                containSum += Duration.between(inc.getDetectedAt(), inc.getContainedAt()).toMinutes() / 60.0;
                containN++;
            }
        }
        return new Double[]{
                detectN  > 0 ? round1(detectSum  / detectN)  : null,
                containN > 0 ? round1(containSum / containN) : null };
    }

    private Double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
