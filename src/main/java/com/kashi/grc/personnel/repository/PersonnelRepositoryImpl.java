package com.kashi.grc.personnel.repository;

import com.kashi.grc.personnel.domain.Personnel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class PersonnelRepositoryImpl implements PersonnelRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long nextPersonRefSequence(Long tenantId) {
        LocalDateTime startOfYear     = LocalDate.now().withDayOfYear(1).atStartOfDay();
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Personnel> p = cq.from(Personnel.class);
        cq.select(cb.count(p)).where(
                cb.equal(p.get("tenantId"), tenantId),
                cb.greaterThanOrEqualTo(p.<LocalDateTime>get("createdAt"), startOfYear),
                cb.lessThan(p.<LocalDateTime>get("createdAt"), startOfNextYear));
        Long count = em.createQuery(cq).getSingleResult();
        return (count != null ? count : 0L) + 1;
    }

    @Override public List<Object[]> countByStatusForTenant(Long t)         { return grouped("status", t); }
    @Override public List<Object[]> countByEmploymentTypeForTenant(Long t) { return grouped("employmentType", t); }
    @Override public List<Object[]> countByScreeningStatusForTenant(Long t){ return grouped("backgroundCheckStatus", t); }

    private List<Object[]> grouped(String attribute, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Personnel> p = cq.from(Personnel.class);
        Path<?> g = p.get(attribute);
        cq.multiselect(g, cb.count(p))
          .where(cb.equal(p.get("tenantId"), tenantId), cb.isFalse(p.get("isDeleted")))
          .groupBy(g);
        return em.createQuery(cq).getResultList();
    }

    /**
     * Excludes OFFBOARDED and out-of-scope people. Screening a leaver is not a
     * gap, and neither is screening somebody the programme does not cover —
     * counting either would make the number meaningless.
     *
     * Exclusions are NOT filtered here: this repository does not know about
     * them, and PersonnelService subtracts the excluded before reporting. Doing
     * it in SQL would duplicate the expiry rule that lives on the entity.
     */
    @Override
    public List<Personnel> findUnscreenedInScope(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Personnel> cq = cb.createQuery(Personnel.class);
        Root<Personnel> p = cq.from(Personnel.class);
        cq.where(
                cb.equal(p.get("tenantId"), tenantId),
                cb.isFalse(p.get("isDeleted")),
                cb.isTrue(p.get("isInScope")),
                cb.notEqual(p.get("status"), Personnel.Status.OFFBOARDED),
                cb.not(p.get("backgroundCheckStatus").in("CLEARED", "NOT_REQUIRED", "WAIVED")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Personnel> findOffboardedWithoutRevocationEvidence(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Personnel> cq = cb.createQuery(Personnel.class);
        Root<Personnel> p = cq.from(Personnel.class);
        cq.where(
                cb.equal(p.get("tenantId"), tenantId),
                cb.isFalse(p.get("isDeleted")),
                cb.equal(p.get("status"), Personnel.Status.OFFBOARDED),
                cb.isNull(p.get("accessRevokedAt")),
                // Somebody who never started never had access to revoke.
                cb.or(cb.isNull(p.get("separationReason")),
                      cb.notEqual(p.get("separationReason"), "NEVER_STARTED")));
        return em.createQuery(cq).getResultList();
    }
}
