package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SiteChatMessageRepositoryImpl implements SiteChatMessageRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<SiteChatMessage> findBySession(Long sessionId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SiteChatMessage> q = cb.createQuery(SiteChatMessage.class);
        Root<SiteChatMessage> m = q.from(SiteChatMessage.class);
        q.select(m).where(cb.equal(m.get("sessionId"), sessionId)).orderBy(cb.asc(m.get("id")));
        return em.createQuery(q).getResultList();
    }

    @Override
    public List<SiteChatMessage> findRecentBySession(Long sessionId, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SiteChatMessage> q = cb.createQuery(SiteChatMessage.class);
        Root<SiteChatMessage> m = q.from(SiteChatMessage.class);
        q.select(m).where(cb.equal(m.get("sessionId"), sessionId)).orderBy(cb.desc(m.get("id")));
        List<SiteChatMessage> rows = new ArrayList<>(em.createQuery(q).setMaxResults(Math.max(1, limit)).getResultList());
        Collections.reverse(rows);
        return rows;
    }

    @Override
    public long countByRoleSince(String role, LocalDateTime since) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<SiteChatMessage> m = q.from(SiteChatMessage.class);
        q.select(cb.count(m)).where(
                cb.equal(m.get("role"), role),
                cb.greaterThanOrEqualTo(m.<LocalDateTime>get("createdAt"), since));
        Long n = em.createQuery(q).getSingleResult();
        return n == null ? 0 : n;
    }

    @Override
    public List<SiteChatMessage> findUnconfidentSince(LocalDateTime since, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SiteChatMessage> q = cb.createQuery(SiteChatMessage.class);
        Root<SiteChatMessage> m = q.from(SiteChatMessage.class);
        q.select(m).where(
                cb.equal(m.get("role"), SiteChatMessage.ASSISTANT),
                cb.isFalse(m.get("confident")),
                cb.greaterThanOrEqualTo(m.<LocalDateTime>get("createdAt"), since))
         .orderBy(cb.desc(m.get("id")));
        return em.createQuery(q).setMaxResults(Math.max(1, limit)).getResultList();
    }
}
