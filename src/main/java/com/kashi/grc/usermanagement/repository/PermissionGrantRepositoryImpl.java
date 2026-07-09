package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Permission;
import com.kashi.grc.usermanagement.domain.PermissionGrant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of PermissionGrantRepositoryCustom. */
public class PermissionGrantRepositoryImpl implements PermissionGrantRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<PermissionGrant> findByRoleIdWithPermission(Long roleId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<PermissionGrant> cq = cb.createQuery(PermissionGrant.class);
        Root<PermissionGrant> g = cq.from(PermissionGrant.class);
        cq.where(cb.equal(g.get("roleId"), roleId));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<PermissionGrant> findActiveGrantsByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return List.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<PermissionGrant> cq = cb.createQuery(PermissionGrant.class);
        Root<PermissionGrant> g = cq.from(PermissionGrant.class);
        cq.where(
                g.get("roleId").in(roleIds),
                cb.isTrue(g.get("granted"))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Object[]> findGrantsForUserRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return List.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<PermissionGrant> g = cq.from(PermissionGrant.class);

        // Scalar subquery: SELECT p.code FROM Permission p WHERE p.id = g.permissionId
        Subquery<String> codeSub = cq.subquery(String.class);
        Root<Permission> p = codeSub.from(Permission.class);
        codeSub.select(p.get("code")).where(cb.equal(p.get("id"), g.get("permissionId")));

        // EXISTS variant used in the row filter — matches "p.code IS NOT NULL" from
        // the former LEFT JOIN (a matching Permission with a non-null code exists)
        Subquery<Long> codeExists = cq.subquery(Long.class);
        Root<Permission> p2 = codeExists.from(Permission.class);
        codeExists.select(cb.literal(1L)).where(
                cb.equal(p2.get("id"), g.get("permissionId")),
                cb.isNotNull(p2.get("code"))
        );

        cq.multiselect(
                cb.coalesce(g.<String>get("permissionCode"), codeSub),
                g.get("granted")
        ).where(
                g.get("roleId").in(roleIds),
                cb.or(cb.isNotNull(g.get("permissionCode")), cb.exists(codeExists))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public void deleteByPermissionId(Long permissionId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<PermissionGrant> cd = cb.createCriteriaDelete(PermissionGrant.class);
        Root<PermissionGrant> g = cd.from(PermissionGrant.class);
        cd.where(cb.equal(g.get("permissionId"), permissionId));
        em.createQuery(cd).executeUpdate();
    }
}
