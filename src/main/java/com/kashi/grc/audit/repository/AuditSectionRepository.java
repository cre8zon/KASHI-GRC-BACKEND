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

    Optional<AuditSection> findBySectionCodeAndFrameworkRef(String sectionCode, String frameworkRef);
}
