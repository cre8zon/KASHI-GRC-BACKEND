package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.ArrayList;
import java.util.List;

import static com.kashi.grc.common.util.Constants.SYSTEM_TENANT_ID;

/** JPA Criteria API implementation of RoleRepositoryCustom. */
public class RoleRepositoryImpl implements RoleRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Role> findAllForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Role> cq = cb.createQuery(Role.class);
        Root<Role> r = cq.from(Role.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.or(cb.equal(r.get("tenantId"), tenantId), cb.isNull(r.get("tenantId"))));
        // Same hard rule as findAllForTenantBySide — SYSTEM roles never leak
        // to a non-system tenant, even though they carry tenant_id = NULL.
        if (!SYSTEM_TENANT_ID.equals(tenantId)) {
            predicates.add(cb.notEqual(r.get("side"), RoleSide.SYSTEM));
        }
        cq.where(predicates.toArray(new Predicate[0]));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Role> findAllForTenantBySide(Long tenantId, RoleSide side) {
        return findAllForTenantBySide(tenantId, side, false);
    }

    @Override
    public List<Role> findAllForTenantBySide(Long tenantId, RoleSide side, boolean includeSuspended) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Role> cq = cb.createQuery(Role.class);
        Root<Role> r = cq.from(Role.class);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.or(cb.equal(r.get("tenantId"), tenantId), cb.isNull(r.get("tenantId"))));
        if (side != null) {
            predicates.add(cb.equal(r.get("side"), side));
        }

        // HARD RULE: side=SYSTEM belongs to the one Kashi System Tenant.
        // The tenant/global predicate above matches `tenantId IS NULL` as
        // "available to everyone", and the existing SYSTEM roles
        // (PLATFORM_ADMIN, PLATFORM_SUPPORT, ...) all carry tenant_id =
        // NULL — so without this, every tenant's role list included the
        // platform-admin roles and anyone with ROLE_MANAGE could assign
        // them. Enforced here at the query layer rather than relying on
        // the stored tenant_id, so it holds for existing rows too, not
        // just newly created ones.
        if (!SYSTEM_TENANT_ID.equals(tenantId)) {
            predicates.add(cb.notEqual(r.get("side"), RoleSide.SYSTEM));
        }

        // Suspended roles are parked, not deleted — excluded from every
        // assignable list, but RBAC admin passes includeSuspended=true so
        // they can still be found, edited and reactivated. `status IS NULL`
        // counts as ACTIVE so rows that predate the column keep working.
        if (!includeSuspended) {
            predicates.add(cb.or(
                    cb.isNull(r.get("status")),
                    cb.notEqual(r.get("status"), "SUSPENDED")));
        }

        cq.where(predicates.toArray(new Predicate[0]));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public long countUsersWithRole(Long roleId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<User> u = cq.from(User.class);
        Join<Object, Object> roles = u.join("roles");
        cq.select(cb.count(u)).where(cb.equal(roles.get("id"), roleId));
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }
}