package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Permission;
import com.kashi.grc.usermanagement.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JPA Criteria API implementation of PermissionRepositoryCustom.
 * Walks the mapped associations User → roles → permissions with real joins —
 * same SQL as the former JPQL "FROM User u JOIN u.roles r JOIN r.permissions p".
 */
public class PermissionRepositoryImpl implements PermissionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Set<Permission> findAllByUserId(Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Permission> cq = cb.createQuery(Permission.class);
        Root<User> u = cq.from(User.class);
        Join<Object, Object> roles = u.join("roles");
        Join<Object, Permission> permissions = roles.join("permissions");

        cq.select(permissions).distinct(true)
                .where(cb.equal(u.get("id"), userId));

        return new LinkedHashSet<>(em.createQuery(cq).getResultList());
    }
}
