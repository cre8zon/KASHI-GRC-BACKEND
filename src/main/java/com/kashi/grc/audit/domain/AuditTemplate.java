package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * AuditTemplate — reusable audit template in the library.
 *
 * Mirrors AssessmentTemplate exactly.
 * tenant_id = null  → global template created by Platform Admin, visible to all orgs
 * tenant_id = X     → private template, visible to org X only
 *
 * Status lifecycle: DRAFT → PUBLISHED → DRAFT (unpublish)
 *
 * Sections linked via AuditTemplateSectionMapping (join table).
 * One template can reuse the same section across multiple templates — zero duplication.
 *
 * auditType drives default workflow selection:
 *   INTERNAL → AUDIT_ENGAGEMENT_INTERNAL workflow
 *   EXTERNAL → AUDIT_ENGAGEMENT_EXTERNAL workflow
 *
 * frameworkRef: free-text compliance framework tag, e.g. "ISO 27001", "SOC 2", "PCI DSS"
 * Used for filtering templates and for auto-tagging engagements.
 */
@Entity
@Table(name = "audit_templates",
        indexes = {
                @Index(name = "idx_audit_tmpl_tenant", columnList = "tenant_id"),
                @Index(name = "idx_audit_tmpl_status", columnList = "status"),
                @Index(name = "idx_audit_tmpl_type",   columnList = "audit_type")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditTemplate extends GlobalOrTenantEntity {

    /** Primary name — maps to template_name column (NOT NULL) */
    @Column(name = "template_name", nullable = false, length = 255)
    private String templateName;

    /** Legacy alias — kept in sync with templateName, maps to name column */
    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** ISO 27001, SOC 2, PCI DSS, HIPAA, NIST CSF, custom, etc. */
    @Column(name = "framework_ref", length = 100)
    private String frameworkRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "audit_type", nullable = false, length = 20)
    @Builder.Default
    private AuditType auditType = AuditType.INTERNAL;

    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /** DRAFT | PUBLISHED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "unpublished_at")
    private LocalDateTime unpublishedAt;

    @Column(name = "created_by")
    private Long createdBy;

    public enum AuditType {
        INTERNAL,  // internal audit — org-side actors only
        EXTERNAL   // external audit — involves AUDITOR + AUDITEE sides
    }
}