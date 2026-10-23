package com.kashi.grc.notification.repository;

import com.kashi.grc.notification.domain.Notification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Root;

import java.time.LocalDateTime;
import java.util.Collection;

public class NotificationRepositoryImpl implements NotificationRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public int markAllRead(Long userId, LocalDateTime at) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<Notification> u = cb.createCriteriaUpdate(Notification.class);
        Root<Notification> n = u.from(Notification.class);
        u.set(n.<LocalDateTime>get("readAt"), at);
        u.where(cb.equal(n.get("userId"), userId), cb.isNull(n.get("readAt")));
        return em.createQuery(u).executeUpdate();
    }

    @Override
    public int markRead(Long userId, Collection<Long> ids, LocalDateTime at) {
        if (ids == null || ids.isEmpty()) return 0;
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<Notification> u = cb.createCriteriaUpdate(Notification.class);
        Root<Notification> n = u.from(Notification.class);
        u.set(n.<LocalDateTime>get("readAt"), at);
        u.where(cb.equal(n.get("userId"), userId), cb.isNull(n.get("readAt")), n.get("id").in(ids));
        return em.createQuery(u).executeUpdate();
    }

    @Override
    public int markReadForEntity(Long userId, String entityType, Long entityId, LocalDateTime at) {
        if (entityType == null || entityId == null) return 0;
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<Notification> u = cb.createCriteriaUpdate(Notification.class);
        Root<Notification> n = u.from(Notification.class);
        u.set(n.<LocalDateTime>get("readAt"), at);
        u.where(cb.equal(n.get("userId"), userId), cb.isNull(n.get("readAt")),
                cb.equal(n.get("entityType"), entityType), cb.equal(n.get("entityId"), entityId));
        return em.createQuery(u).executeUpdate();
    }
}