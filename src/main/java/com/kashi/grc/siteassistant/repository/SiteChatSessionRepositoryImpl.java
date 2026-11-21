package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import java.util.Optional;

public class SiteChatSessionRepositoryImpl implements SiteChatSessionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<SiteChatSession> findByTokenHash(String tokenHash) {
        return one("tokenHash", tokenHash);
    }

    @Override
    public Optional<SiteChatSession> findByEnquiryId(Long enquiryId) {
        return one("enquiryId", enquiryId);
    }

    private Optional<SiteChatSession> one(String field, Object value) {
        if (value == null) return Optional.empty();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SiteChatSession> q = cb.createQuery(SiteChatSession.class);
        Root<SiteChatSession> s = q.from(SiteChatSession.class);
        q.select(s).where(cb.equal(s.get(field), value)).orderBy(cb.desc(s.get("id")));
        return em.createQuery(q).setMaxResults(1).getResultList().stream().findFirst();
    }
}
