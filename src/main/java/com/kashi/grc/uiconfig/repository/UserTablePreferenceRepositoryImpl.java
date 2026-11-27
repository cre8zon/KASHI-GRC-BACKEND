package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UserTablePreference;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import java.util.Optional;

public class UserTablePreferenceRepositoryImpl implements UserTablePreferenceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<UserTablePreference> findOne(Long userId, Long tenantId, String tableKey) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UserTablePreference> q = cb.createQuery(UserTablePreference.class);
        Root<UserTablePreference> p = q.from(UserTablePreference.class);
        q.select(p).where(
                cb.equal(p.get("userId"), userId),
                cb.equal(p.get("tenantId"), tenantId),
                cb.equal(p.get("tableKey"), tableKey));
        return em.createQuery(q).setMaxResults(1).getResultList().stream().findFirst();
    }

    @Override
    public int deleteOne(Long userId, Long tenantId, String tableKey) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<UserTablePreference> d = cb.createCriteriaDelete(UserTablePreference.class);
        Root<UserTablePreference> p = d.from(UserTablePreference.class);
        d.where(
                cb.equal(p.get("userId"), userId),
                cb.equal(p.get("tenantId"), tenantId),
                cb.equal(p.get("tableKey"), tableKey));
        return em.createQuery(d).executeUpdate();
    }
}
