package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSection;
import java.util.List;

/** Criteria API fragment for AuditSectionRepository (path-tree queries). */
public interface AuditSectionRepositoryCustom {

    /** Root sections (parentId IS NULL) visible to a tenant (global + tenant). */
    List<AuditSection> findRootSections(Long tenantId);

    /** All descendants at any depth (path LIKE prefix%), excluding the section itself. */
    List<AuditSection> findAllDescendants(Long sectionId, String pathPrefix);

    /** Full subtree including the section itself. */
    List<AuditSection> findSubtree(String pathPrefix);

    /** All sections whose path contains this ancestor ID ('%/id/%'). */
    List<AuditSection> findAllUnderAncestor(Long ancestorId);
}
