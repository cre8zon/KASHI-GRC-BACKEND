package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * AuditSectionInstance — runtime snapshot of one AuditSection node.
 *
 * ── TREE PRESERVATION ────────────────────────────────────────────────────────
 * The full section tree is snapshotted at engagement instantiation.
 * Each AuditSectionInstance has:
 *   parentInstanceId → points to the AuditSectionInstance of its parent node
 *                      (NOT the library parentId — these are instance IDs)
 *   path             → materialized path using INSTANCE IDs for subtree queries
 *   depth            → pre-computed depth in the snapshot tree
 *
 * This fully isolates the running engagement from any library restructuring.
 * The engagement tree is a frozen copy — moving sections in the library
 * has zero effect on running engagements.
 *
 * ── ASSIGNMENT ───────────────────────────────────────────────────────────────
 * Assignment only makes sense at the level the auditor actually works.
 * Typically lead auditor assigns top-level or mid-level sections;
 * auditors can further sub-assign their children to colleagues.
 *
 * assignedAuditorId    — auditor responsible for this section subtree
 * auditeeAssignedUserId — auditee providing evidence for this section subtree
 *
 * Children inherit parent assignment unless overridden explicitly.
 * (Inheritance is computed at query time by the service — not stored.)
 *
 * ── SUBMISSION LOCKING ───────────────────────────────────────────────────────
 * submittedAt non-null → this section node is locked (auditor side)
 * auditeeSubmittedAt non-null → auditee evidence is locked for this node
 * Locking a parent does NOT auto-lock children — each node is independently lockable.
 * (Bulk-submit of a subtree is handled by the service iterating the subtree.)
 */
@Entity
@Table(name = "audit_section_instances",
        indexes = {
                @Index(name = "idx_asi_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_asi_engagement",  columnList = "engagement_id"),
                @Index(name = "idx_asi_parent",      columnList = "parent_instance_id"),
                @Index(name = "idx_asi_path",        columnList = "path"),
                @Index(name = "idx_asi_orig_sec",    columnList = "original_section_id"),
                @Index(name = "idx_asi_auditor",     columnList = "assigned_auditor_id"),
                @Index(name = "idx_asi_auditee",     columnList = "auditee_assigned_user_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditSectionInstance extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    @Column(name = "template_instance_id", nullable = false)
    private Long templateInstanceId;

    // ── Tree structure (instance IDs, not library IDs) ────────────────────────

    /**
     * Parent AuditSectionInstance ID. Null = root section in this engagement.
     * Uses instance IDs — fully isolated from library structure.
     */
    @Column(name = "parent_instance_id")
    private Long parentInstanceId;

    /** Materialized path of instance IDs: "/root_inst_id/child_inst_id/.../this_id/" */
    @Column(name = "path", nullable = false, length = 500)
    private String path;

    @Column(name = "depth", nullable = false)
    @Builder.Default
    private Integer depth = 0;

    // ── Library reference (frozen) ────────────────────────────────────────────

    @Column(name = "original_section_id")
    private Long originalSectionId;

    // ── Snapshots (frozen at instantiation — never updated from library) ──────

    @Column(name = "section_name_snapshot", nullable = false, length = 500)
    private String sectionNameSnapshot;

    @Column(name = "section_code_snapshot", length = 100)
    private String sectionCodeSnapshot;

    @Column(name = "description_snapshot", columnDefinition = "TEXT")
    private String descriptionSnapshot;

    @Column(name = "framework_ref_snapshot", length = 100)
    private String frameworkRefSnapshot;

    /** Order among siblings in the snapshot tree */
    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    // ── Auditor-side assignment + submission ──────────────────────────────────

    @Column(name = "assigned_auditor_id")
    private Long assignedAuditorId;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopened_by")
    private Long reopenedBy;

    // ── Auditee-side assignment + submission ──────────────────────────────────

    @Column(name = "auditee_assigned_user_id")
    private Long auditeeAssignedUserId;

    @Column(name = "auditee_submitted_at")
    private LocalDateTime auditeeSubmittedAt;

    @Column(name = "auditee_submitted_by")
    private Long auditeeSubmittedBy;

    @Column(name = "auditee_reopened_at")
    private LocalDateTime auditeeReopenedAt;

    @Column(name = "auditee_reopened_by")
    private Long auditeeReopenedBy;
}