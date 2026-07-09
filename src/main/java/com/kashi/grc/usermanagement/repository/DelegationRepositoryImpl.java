package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Delegation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** JPA Criteria API implementation of DelegationRepositoryCustom. */
public class DelegationRepositoryImpl implements DelegationRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Delegation> findActive(Long tenantId, Long userId, String scopeType, LocalDateTime now) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Delegation> cq = cb.createQuery(Delegation.class);
        Root<Delegation> d = cq.from(Delegation.class);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(d.get("tenantId"), tenantId));
        predicates.add(cb.equal(d.get("status"), "ACTIVE"));
        predicates.add(cb.greaterThan(d.get("endDate"), now));
        if (userId != null) {
            predicates.add(cb.or(
                    cb.equal(d.get("delegatorUserId"), userId),
                    cb.equal(d.get("delegateeUserId"), userId)));
        }
        if (scopeType != null) {
            predicates.add(cb.equal(d.get("scopeType"), scopeType));
        }
        cq.where(predicates.toArray(new Predicate[0]));
        return em.createQuery(cq).getResultList();
    }

    private long countActive(String userField, Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Delegation> d = cq.from(Delegation.class);
        cq.select(cb.count(d)).where(
                cb.equal(d.get(userField), userId),
                cb.equal(d.get("status"), "ACTIVE"),
                cb.greaterThan(d.get("endDate"), LocalDateTime.now())
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public long countActiveDelegationsToMe(Long userId) {
        return countActive("delegateeUserId", userId);
    }

    @Override
    public long countActiveDelegationsByMe(Long userId) {
        return countActive("delegatorUserId", userId);
    }
}
