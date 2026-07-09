package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserPermissionOverride;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.List;

/** JPA Criteria API implementation of UserPermissionOverrideRepositoryCustom. */
public class UserPermissionOverrideRepositoryImpl implements UserPermissionOverrideRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<UserPermissionOverride> findActiveByUserId(Long userId, LocalDateTime now) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UserPermissionOverride> cq = cb.createQuery(UserPermissionOverride.class);
        Root<UserPermissionOverride> o = cq.from(UserPermissionOverride.class);
        cq.where(
                cb.equal(o.get("userId"), userId),
                cb.isTrue(o.get("isActive")),
                cb.or(cb.isNull(o.get("expiresAt")), cb.greaterThan(o.get("expiresAt"), now))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public void deleteByPermissionId(Long permissionId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<UserPermissionOverride> cd = cb.createCriteriaDelete(UserPermissionOverride.class);
        Root<UserPermissionOverride> o = cd.from(UserPermissionOverride.class);
        cd.where(cb.equal(o.get("permissionId"), permissionId));
        em.createQuery(cd).executeUpdate();
    }
}
