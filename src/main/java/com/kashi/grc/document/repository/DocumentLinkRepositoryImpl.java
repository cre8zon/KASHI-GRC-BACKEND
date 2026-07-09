package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.domain.DocumentLink;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.Collection;
import java.util.List;

/** JPA Criteria API implementation of DocumentLinkRepositoryCustom. */
public class DocumentLinkRepositoryImpl implements DocumentLinkRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<DocumentLink> findActiveByEntity(String entityType, Long entityId, String linkType) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DocumentLink> cq = cb.createQuery(DocumentLink.class);
        Root<DocumentLink> dl = cq.from(DocumentLink.class);
        Root<Document> d = cq.from(Document.class);

        cq.select(dl).where(
                cb.equal(d.get("id"), dl.get("documentId")),   // the join condition
                cb.equal(dl.get("entityType"), entityType),
                cb.equal(dl.get("entityId"), entityId),
                cb.equal(dl.get("linkType"), linkType),
                cb.equal(d.get("status"), "ACTIVE")
        );
        cq.orderBy(cb.asc(dl.get("displayOrder")), cb.desc(d.get("version")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<DocumentLink> findAllActiveByEntity(String entityType, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DocumentLink> cq = cb.createQuery(DocumentLink.class);
        Root<DocumentLink> dl = cq.from(DocumentLink.class);
        Root<Document> d = cq.from(Document.class);

        cq.select(dl).where(
                cb.equal(d.get("id"), dl.get("documentId")),
                cb.equal(dl.get("entityType"), entityType),
                cb.equal(dl.get("entityId"), entityId),
                cb.equal(d.get("status"), "ACTIVE")
        );
        cq.orderBy(cb.asc(dl.get("linkType")), cb.asc(dl.get("displayOrder")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<DocumentLink> findReportVersions(String entityType, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DocumentLink> cq = cb.createQuery(DocumentLink.class);
        Root<DocumentLink> dl = cq.from(DocumentLink.class);
        Root<Document> d = cq.from(Document.class);

        cq.select(dl).where(
                cb.equal(d.get("id"), dl.get("documentId")),
                cb.equal(dl.get("entityType"), entityType),
                cb.equal(dl.get("entityId"), entityId),
                cb.equal(dl.get("linkType"), "REPORT")
        );
        cq.orderBy(cb.desc(d.get("version")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public long countActiveAttachments(String entityType, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<DocumentLink> dl = cq.from(DocumentLink.class);
        Root<Document> d = cq.from(Document.class);

        cq.select(cb.count(dl)).where(
                cb.equal(d.get("id"), dl.get("documentId")),
                cb.equal(dl.get("entityType"), entityType),
                cb.equal(dl.get("entityId"), entityId),
                cb.equal(dl.get("linkType"), "ATTACHMENT"),
                cb.equal(d.get("status"), "ACTIVE")
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<Object[]> countActiveAttachmentsBulk(String entityType, Collection<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<DocumentLink> dl = cq.from(DocumentLink.class);
        Root<Document> d = cq.from(Document.class);

        cq.multiselect(dl.get("entityId"), cb.count(dl))
                .where(
                        cb.equal(d.get("id"), dl.get("documentId")),
                        cb.equal(dl.get("entityType"), entityType),
                        dl.get("entityId").in(entityIds),
                        cb.equal(dl.get("linkType"), "ATTACHMENT"),
                        cb.equal(d.get("status"), "ACTIVE")
                )
                .groupBy(dl.get("entityId"));
        return em.createQuery(cq).getResultList();
    }
}
