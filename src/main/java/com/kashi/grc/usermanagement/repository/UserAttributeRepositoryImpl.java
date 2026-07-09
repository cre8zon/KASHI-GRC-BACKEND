package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserAttribute;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * JPA Criteria API implementation of UserAttributeRepositoryCustom.
 * a.user.id navigates the @ManyToOne FK — root.get("user").get("id") compiles
 * to the user_id column without a join, in both queries and CriteriaDelete.
 */
public class UserAttributeRepositoryImpl implements UserAttributeRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<UserAttribute> findByUserId(Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UserAttribute> cq = cb.createQuery(UserAttribute.class);
        Root<UserAttribute> a = cq.from(UserAttribute.class);
        cq.where(cb.equal(a.get("user").get("id"), userId));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public Optional<UserAttribute> findByUserIdAndAttributeKey(Long userId, String key) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UserAttribute> cq = cb.createQuery(UserAttribute.class);
        Root<UserAttribute> a = cq.from(UserAttribute.class);
        cq.where(
                cb.equal(a.get("user").get("id"), userId),
                cb.equal(a.get("attributeKey"), key)
        );
        List<UserAttribute> result = em.createQuery(cq).setMaxResults(1).getResultList();
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    @Transactional
    public void deleteByUserIdAndAttributeKey(Long userId, String key) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<UserAttribute> cd = cb.createCriteriaDelete(UserAttribute.class);
        Root<UserAttribute> a = cd.from(UserAttribute.class);
        cd.where(
                cb.equal(a.get("user").get("id"), userId),
                cb.equal(a.get("attributeKey"), key)
        );
        em.createQuery(cd).executeUpdate();
    }

    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<UserAttribute> cd = cb.createCriteriaDelete(UserAttribute.class);
        Root<UserAttribute> a = cd.from(UserAttribute.class);
        cd.where(cb.equal(a.get("user").get("id"), userId));
        em.createQuery(cd).executeUpdate();
    }
}
