package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditSectionRepository extends JpaRepository<AuditSection, Long> {

    // ── Library browsing ──────────────────────────────────────────────────────

    List<AuditSection> findByTenantIdIsNullOrTenantId(Long tenantId);

    /** All root sections (top-level, no parent) */
    List<AuditSection> findByParentIdIsNullOrderByOrderNoAsc();

    /** All root sections for a tenant (global + tenant-specific) */
    @Query("""
        SELECT s FROM AuditSection s
        WHERE s.parentId IS NULL
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
        ORDER BY s.orderNo ASC
    """)
    List<AuditSection> findRootSections(@Param("tenantId") Long tenantId);

    /** Direct children of a section (one level down) */
    List<AuditSection> findByParentIdOrderByOrderNoAsc(Long parentId);

    /**
     * All descendants of a section at any depth.
     * Uses path LIKE query — fast with idx_audit_sec_path index.
     * path of target = "/4/12/" → descendants have path LIKE "/4/12/%"
     */
    @Query("""
        SELECT s FROM AuditSection s
        WHERE s.path LIKE CONCAT(:pathPrefix, '%')
          AND s.id != :sectionId
        ORDER BY s.path ASC, s.orderNo ASC
    """)
    List<AuditSection> findAllDescendants(
        @Param("sectionId") Long sectionId,
        @Param("pathPrefix") String pathPrefix
    );

    /** Full subtree including the section itself — root + all descendants */
    @Query("""
        SELECT s FROM AuditSection s
        WHERE s.path LIKE CONCAT(:pathPrefix, '%')
        ORDER BY s.path ASC, s.orderNo ASC
    """)
    List<AuditSection> findSubtree(@Param("pathPrefix") String pathPrefix);

    /** All sections whose path contains this ancestor ID */
    @Query("""
        SELECT s FROM AuditSection s
        WHERE s.path LIKE CONCAT('%/', :ancestorId, '/%')
        ORDER BY s.path ASC
    """)
    List<AuditSection> findAllUnderAncestor(@Param("ancestorId") Long ancestorId);

    Optional<AuditSection> findBySectionCodeAndFrameworkRef(String sectionCode, String frameworkRef);
}
