package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.ArrayList;
import java.util.List;

/** JPA Criteria API implementation of RoleRepositoryCustom. */
public class RoleRepositoryImpl implements RoleRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Role> findAllForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Role> cq = cb.createQuery(Role.class);
        Root<Role> r = cq.from(Role.class);
        cq.where(cb.or(cb.equal(r.get("tenantId"), tenantId), cb.isNull(r.get("tenantId"))));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Role> findAllForTenantBySide(Long tenantId, RoleSide side) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Role> cq = cb.createQuery(Role.class);
        Root<Role> r = cq.from(Role.class);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.or(cb.equal(r.get("tenantId"), tenantId), cb.isNull(r.get("tenantId"))));
        if (side != null) {
            predicates.add(cb.equal(r.get("side"), side));
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
