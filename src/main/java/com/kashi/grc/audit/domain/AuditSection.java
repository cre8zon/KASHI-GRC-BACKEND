package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditSection — a node in the reusable section tree.
 *
 * ── TREE MODEL ───────────────────────────────────────────────────────────────
 * Unlimited depth via adjacency list (parentId) + materialized path.
 *
 *   AuditSection (root, parentId=null)
 *     └── AuditSection (sub-section, parentId=root.id)
 *           └── AuditSection (sub-sub-section, parentId=sub.id)
 *                 └── [controls attached here at any depth]
 *
 * parentId = null  → top-level section (e.g. "A — Information Security Policies")
 * parentId = X     → child of section X (e.g. "A.5 — Policies for Information Security")
 *
 * ── MATERIALIZED PATH ────────────────────────────────────────────────────────
 * path stores the full ancestor chain: "1/4/12/37"
 * Built by AuditSectionService on create/move. Never null — root = "/" + id + "/"
 *
 * WHY BOTH:
 *   parentId → navigate one level up/down (cheap)
 *   path     → subtree queries without recursive CTEs (critical for MySQL <8.0)
 *
 *   "Find all sections under A.9" = WHERE path LIKE '/4/%'
 *   "Find depth"                  = (LENGTH(path) - LENGTH(REPLACE(path,'/',''))) - 1
 *   "Find ancestors"              = WHERE id IN (4, 9, 23) parsed from path
 *
 * ── REUSE ────────────────────────────────────────────────────────────────────
 * Sections are library entities (GlobalOrTenantEntity — tenant_id nullable).
 * They are linked to AuditTemplate via AuditTemplateSectionMapping.
 * One section tree can appear in multiple templates.
 * Controls attach via AuditSectionControlMapping at any depth level.
 *
 * ── DEPTH CONVENTION ─────────────────────────────────────────────────────────
 * depth=0 → top-level (domain / function)      e.g. "A — Org Controls"
 * depth=1 → section                             e.g. "A.5"
 * depth=2 → sub-section                         e.g. "A.5.1"
 * depth=N → leaf section (controls attached)    e.g. "A.5.1.1"
 *
 * Controls can attach at any depth — a section at depth=1 can have controls
 * directly if the framework doesn't subdivide further.
 */
@Entity
@Table(name = "audit_sections",
    indexes = {
        @Index(name = "idx_audit_sec_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_audit_sec_parent",   columnList = "parent_id"),
        @Index(name = "idx_audit_sec_path",     columnList = "path"),
        @Index(name = "idx_audit_sec_code",     columnList = "section_code"),
        @Index(name = "idx_audit_sec_framework",columnList = "framework_ref")
    }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditSection extends GlobalOrTenantEntity {

    // ── Tree structure ────────────────────────────────────────────────────────

    /**
     * Parent section ID. Null = root / top-level section.
     * No @ManyToOne — avoids lazy-loading traps on tree traversal.
     */
    @Column(name = "parent_id")
    private Long parentId;

    /**
     * Materialized path: "/" + ancestor_id + "/" + ... + "/" + this_id + "/"
     * e.g. root node 4 → "/4/"
     *      child 12    → "/4/12/"
     *      grandchild  → "/4/12/37/"
     *
     * Set by AuditSectionService.create() and updatePath().
     * Always non-null. Max length 500 supports ~40 levels of nesting.
     */
    @Column(name = "path", nullable = false, length = 500)
    private String path;

    /**
     * Pre-computed depth (0 = root).
     * Stored to avoid re-computing from path on every read.
     * depth = (path occurrences of '/') - 2
     */
    @Column(name = "depth", nullable = false)
    @Builder.Default
    private Integer depth = 0;

    // ── Identity ──────────────────────────────────────────────────────────────

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Framework-specific code at this level.
     * e.g. "A", "A.5", "A.5.1", "A.5.1.1" (ISO 27001)
     *       "CC6", "CC6.1" (SOC 2 TSC)
     *       "PR.AC", "PR.AC-1" (NIST CSF)
     */
    @Column(name = "section_code", length = 100)
    private String sectionCode;

    @Column(name = "framework_ref", length = 100)
    private String frameworkRef;

    /** Display order among siblings (children of the same parent) */
    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    @Column(name = "created_by")
    private Long createdBy;
}
