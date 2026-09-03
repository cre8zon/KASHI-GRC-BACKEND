package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.usermanagement.domain.UserTenantMembership;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Criteria implementation of UserTenantMembershipRepositoryCustom.
 *
 * Matches the AuditPolicyRepositoryImpl pattern — Custom interface plus an Impl
 * that Spring Data wires in by name — so no @Query annotation is needed.
 */
public class UserTenantMembershipRepositoryImpl implements UserTenantMembershipRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AuditFirmSummary> findActiveFirmsForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditFirmSummary> cq = cb.createQuery(AuditFirmSummary.class);

        Root<UserTenantMembership> m = cq.from(UserTenantMembership.class);

        // firmTenantId is a raw Long, not a @ManyToOne, so there is no association
        // to join through. A second root with an explicit equality is the Criteria
        // equivalent of the cross join — the same thing JPQL would express as
        // "FROM UserTenantMembership m, Tenant t WHERE t.id = m.firmTenantId".
        Root<Tenant> t = cq.from(Tenant.class);

        Predicate joinCondition = cb.equal(t.get("id"), m.get("firmTenantId"));

        Predicate scope = cb.and(
                cb.equal(m.get("tenantId"), tenantId),
                cb.isNotNull(m.get("firmTenantId")),
                cb.equal(m.get("status"), "ACTIVE"),
                // An expired grant is not access. Included here rather than left to
                // the caller, because a firm whose window has closed should not be
                // offered as a choice at all.
                cb.or(cb.isNull(m.get("accessExpiresAt")),
                        cb.greaterThan(m.<LocalDateTime>get("accessExpiresAt"),
                                cb.literal(LocalDateTime.now()))));

        // cb.construct maps straight onto the record, so callers get named
        // accessors instead of positional array casts.
        cq.select(cb.construct(AuditFirmSummary.class,
                        m.get("firmTenantId"),
                        t.get("name"),
                        cb.countDistinct(m.get("userId"))))
                .where(cb.and(joinCondition, scope))
                .groupBy(m.get("firmTenantId"), t.get("name"))
                .orderBy(cb.asc(t.get("name")));

        return em.createQuery(cq).getResultList();
    }
}