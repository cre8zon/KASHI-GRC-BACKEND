package com.kashi.grc.comment.repository;

import com.kashi.grc.comment.domain.EntityComment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of EntityCommentRepositoryCustom. */
public class EntityCommentRepositoryImpl implements EntityCommentRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<EntityComment> findVisible(EntityComment.EntityType type, Long entityId,
                                           List<EntityComment.Visibility> visibilities) {
        if (visibilities == null || visibilities.isEmpty()) return List.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<EntityComment> cq = cb.createQuery(EntityComment.class);
        Root<EntityComment> c = cq.from(EntityComment.class);
        cq.where(
                cb.equal(c.get("entityType"), type),
                cb.equal(c.get("entityId"), entityId),
                c.get("visibility").in(visibilities)
        );
        cq.orderBy(cb.asc(c.get("createdAt")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public long countOpenRevisionRequests(Long questionInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<EntityComment> c = cq.from(EntityComment.class);

        // NOT EXISTS (SELECT 1 FROM EntityComment r
        //             WHERE r.parentCommentId = c.id AND r.commentType = RESOLVED)
        Subquery<Long> resolvedChild = cq.subquery(Long.class);
        Root<EntityComment> r = resolvedChild.from(EntityComment.class);
        resolvedChild.select(cb.literal(1L)).where(
                cb.equal(r.get("parentCommentId"), c.get("id")),
                cb.equal(r.get("commentType"), EntityComment.CommentType.RESOLVED)
        );

        cq.select(cb.count(c)).where(
                cb.equal(c.get("questionInstanceId"), questionInstanceId),
                cb.equal(c.get("commentType"), EntityComment.CommentType.REVISION_REQUEST),
                cb.not(cb.exists(resolvedChild))
        );
        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }
}
