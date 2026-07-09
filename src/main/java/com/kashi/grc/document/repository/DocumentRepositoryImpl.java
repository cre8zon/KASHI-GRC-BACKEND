package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.Document;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Criteria API implementation of DocumentRepositoryCustom.
 * The status-guarded updates keep the WHERE status = '<expected>' clause, so
 * the returned row count still signals whether the transition happened.
 */
public class DocumentRepositoryImpl implements DocumentRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Document> findAbandonedUploads(LocalDateTime cutoff) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Document> cq = cb.createQuery(Document.class);
        Root<Document> d = cq.from(Document.class);
        cq.where(
                cb.equal(d.get("status"), "PENDING"),
                cb.lessThan(d.get("createdAt"), cutoff)
        );
        return em.createQuery(cq).getResultList();
    }

    private int transition(Long id, String fromStatus, String toStatus) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<Document> cu = cb.createCriteriaUpdate(Document.class);
        Root<Document> d = cu.from(Document.class);
        cu.set(d.get("status"), toStatus)
          .where(
                  cb.equal(d.get("id"), id),
                  cb.equal(d.get("status"), fromStatus)
          );
        return em.createQuery(cu).executeUpdate();
    }

    @Override
    public int markDeleted(Long id) {
        return transition(id, "PENDING", "DELETED");
    }

    @Override
    public int markSuperseded(Long id) {
        return transition(id, "ACTIVE", "SUPERSEDED");
    }
}
