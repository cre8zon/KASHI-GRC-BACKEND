package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.TenantSubscription;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.util.List;

/** JPA Criteria API implementation of TenantSubscriptionRepositoryCustom. */
public class TenantSubscriptionRepositoryImpl implements TenantSubscriptionRepositoryCustom {

    /** Statuses that still occupy their term (CANCELLED runs to term end). */
    private static final List<String> LIVE = List.of("TRIAL", "ACTIVE", "PAST_DUE", "CANCELLED");
    private static final List<String> PAID_LIVE = List.of("ACTIVE", "PAST_DUE", "CANCELLED");

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<TenantSubscription> findLiveOn(Long tenantId, LocalDate day) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TenantSubscription> cq = cb.createQuery(TenantSubscription.class);
        Root<TenantSubscription> s = cq.from(TenantSubscription.class);

        cq.select(s).where(
                cb.equal(s.get("tenantId"), tenantId),
                s.get("status").in(LIVE),
                cb.lessThanOrEqualTo(s.<LocalDate>get("termStart"), day),
                cb.greaterThan(s.<LocalDate>get("termEnd"), day)
        );
        cq.orderBy(cb.desc(s.get("termStart")), cb.desc(s.get("id")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<TenantSubscription> findAllLiveOn(LocalDate day) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TenantSubscription> cq = cb.createQuery(TenantSubscription.class);
        Root<TenantSubscription> s = cq.from(TenantSubscription.class);

        cq.select(s).where(
                s.get("status").in(LIVE),
                cb.lessThanOrEqualTo(s.<LocalDate>get("termStart"), day),
                cb.greaterThan(s.<LocalDate>get("termEnd"), day)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<TenantSubscription> findLapsed(LocalDate day) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TenantSubscription> cq = cb.createQuery(TenantSubscription.class);
        Root<TenantSubscription> s = cq.from(TenantSubscription.class);

        cq.select(s).where(
                s.get("status").in(LIVE),
                cb.lessThanOrEqualTo(s.<LocalDate>get("termEnd"), day)
        );
        cq.orderBy(cb.asc(s.get("termEnd")), cb.asc(s.get("id")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<TenantSubscription> findRenewingBetween(LocalDate from, LocalDate to) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TenantSubscription> cq = cb.createQuery(TenantSubscription.class);
        Root<TenantSubscription> s = cq.from(TenantSubscription.class);

        cq.select(s).where(
                s.get("status").in(PAID_LIVE),
                cb.greaterThan(s.<LocalDate>get("termEnd"), from),
                cb.lessThanOrEqualTo(s.<LocalDate>get("termEnd"), to)
        );
        cq.orderBy(cb.asc(s.get("termEnd")));
        return em.createQuery(cq).getResultList();
    }
}