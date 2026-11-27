package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatSourcePage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Optional;

public class SiteChatSourcePageRepositoryImpl implements SiteChatSourcePageRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<SiteChatSourcePage> findByPath(String path) {
        if (path == null) return Optional.empty();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SiteChatSourcePage> q = cb.createQuery(SiteChatSourcePage.class);
        Root<SiteChatSourcePage> p = q.from(SiteChatSourcePage.class);
        q.select(p).where(cb.equal(p.get("path"), path));
        return em.createQuery(q).setMaxResults(1).getResultList().stream().findFirst();
    }

    @Override
    public List<SiteChatSourcePage> findActive() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SiteChatSourcePage> q = cb.createQuery(SiteChatSourcePage.class);
        Root<SiteChatSourcePage> p = q.from(SiteChatSourcePage.class);
        q.select(p).where(cb.isTrue(p.get("active"))).orderBy(cb.asc(p.get("path")));
        return em.createQuery(q).getResultList();
    }
}
