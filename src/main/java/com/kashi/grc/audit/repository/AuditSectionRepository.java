package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Path-tree queries live in AuditSectionRepositoryCustom (JPA Criteria API).
 */
@Repository
public interface AuditSectionRepository
        extends JpaRepository<AuditSection, Long>, AuditSectionRepositoryCustom {

    // ── Library browsing ──────────────────────────────────────────────────────

    List<AuditSection> findByTenantIdIsNullOrTenantId(Long tenantId);

    List<AuditSection> findByParentIdIsNullOrderByOrderNoAsc();

    List<AuditSection> findByParentIdOrderByOrderNoAsc(Long parentId);

    /**
     * Batch counterpart to findByParentIdOrderByOrderNoAsc — ONE query for a
     * whole level of the tree (all children of a set of parent ids) instead
     * of one call per parent. Used by AuditSectionService's BFS-batched
     * template snapshot to fetch the library section tree level-by-level
     * (a handful of queries regardless of tree width) instead of one query
     * per node (as many queries as there are sections).
     */
    List<AuditSection> findByParentIdInOrderByOrderNoAsc(java.util.Collection<Long> parentIds);

    Optional<AuditSection> findBySectionCodeAndFrameworkRef(String sectionCode, String frameworkRef);

    /**
     * Indexed lookups for CSV library import (AuditCsvImportService) — were
     * full findAll()+filter-in-Java per row (worst offender: up to 3 findAll()
     * calls PER CONTROL row for section resolution — exact code match, prefix
     * fallback, and control-code-derived prefix match). For a few hundred
     * control rows that was potentially 1000+ full, unfiltered table scans of
     * every section across every tenant just to resolve one target section.
     */
    List<AuditSection> findBySectionCodeAndTenantId(String sectionCode, Long tenantId);

    List<AuditSection> findBySectionCodeStartingWithAndTenantId(String sectionCodePrefix, Long tenantId);

    Optional<AuditSection> findByNameAndTenantIdAndParentId(String name, Long tenantId, Long parentId);

    Optional<AuditSection> findByNameAndTenantIdAndParentIdIsNull(String name, Long tenantId);
}