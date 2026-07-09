package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of AuditSectionRepositoryCustom.
 * Path LIKE queries remain index-friendly (prefix match on idx_audit_sec_path).
 */
public class AuditSectionRepositoryImpl implements AuditSectionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AuditSection> findRootSections(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditSection> cq = cb.createQuery(AuditSection.class);
        Root<AuditSection> s = cq.from(AuditSection.class);
        cq.where(
                cb.isNull(s.get("parentId")),
                cb.or(cb.isNull(s.get("tenantId")), cb.equal(s.get("tenantId"), tenantId))
        );
        cq.orderBy(cb.asc(s.get("orderNo")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditSection> findAllDescendants(Long sectionId, String pathPrefix) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditSection> cq = cb.createQuery(AuditSection.class);
        Root<AuditSection> s = cq.from(AuditSection.class);
        cq.where(
                cb.like(s.get("path"), pathPrefix + "%"),
                cb.notEqual(s.get("id"), sectionId)
        );
        cq.orderBy(cb.asc(s.get("path")), cb.asc(s.get("orderNo")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditSection> findSubtree(String pathPrefix) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditSection> cq = cb.createQuery(AuditSection.class);
        Root<AuditSection> s = cq.from(AuditSection.class);
        cq.where(cb.like(s.get("path"), pathPrefix + "%"));
        cq.orderBy(cb.asc(s.get("path")), cb.asc(s.get("orderNo")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditSection> findAllUnderAncestor(Long ancestorId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditSection> cq = cb.createQuery(AuditSection.class);
        Root<AuditSection> s = cq.from(AuditSection.class);
        cq.where(cb.like(s.get("path"), "%/" + ancestorId + "/%"));
        cq.orderBy(cb.asc(s.get("path")));
        return em.createQuery(cq).getResultList();
    }
}
