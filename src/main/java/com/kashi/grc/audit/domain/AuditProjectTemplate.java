package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditProjectTemplate — planning join table: AuditProject ↔ AuditTemplate.
 *
 * ── ARCHITECTURE ─────────────────────────────────────────────────────────────
 *
 *  LIBRARY (Platform Admin):
 *    AuditTemplate ──< AuditTemplateSectionMapping >── AuditSection tree
 *                      AuditSection ──< AuditSectionControlMapping >── AuditControl
 *
 *  PLANNING (library references only — no instances):
 *    AuditProject ──< AuditProjectTemplate >── AuditTemplate  ← THIS ENTITY
 *
 *  EXECUTION (100% isolated — snapshotted when engagement starts):
 *    AuditProject ──< AuditEngagement ──> AuditEngagementTemplateInstance
 *                                         AuditSectionInstance tree
 *                                         AuditControlInstance
 *
 * ── LIFECYCLE ────────────────────────────────────────────────────────────────
 *   1. POST /v1/audit/projects/{id}/templates/{templateId}
 *      → adds AuditProjectTemplate (planning reference, no snapshot yet)
 *   2. POST /v1/audit/projects/{id}/templates/{templateId}/start
 *      → creates AuditEngagement → snapshotTemplate() → 100% isolated instances
 *      → sets engagementId on this row (prevents double-start)
 *
 * After snapshot: ZERO FK exists between instance tables and library tables.
 */
@Entity
@Table(
        name = "audit_project_templates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_audit_proj_tmpl",
                columnNames = {"project_id", "template_id"}
        )
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditProjectTemplate extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    /** Optional planning note — "Q1 2026", "External auditor", etc. */
    @Column(name = "note", length = 500)
    private String note;

    /**
     * Set when POST .../start creates an engagement from this plan.
     * Non-null = already started; prevents double-starting the same plan entry.
     */
    @Column(name = "engagement_id", unique = true)
    private Long engagementId;
}