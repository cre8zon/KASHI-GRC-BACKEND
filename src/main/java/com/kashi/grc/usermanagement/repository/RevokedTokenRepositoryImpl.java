package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.RevokedToken;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public class RevokedTokenRepositoryImpl implements RevokedTokenRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public int purgeExpiredBefore(LocalDateTime cutoff) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<RevokedToken> cd = cb.createCriteriaDelete(RevokedToken.class);
        Root<RevokedToken> r = cd.from(RevokedToken.class);
        cd.where(cb.lessThan(r.get("expiresAt"), cutoff));
        return em.createQuery(cd).executeUpdate();
    }
}
