package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Join table: AuditTemplate ↔ AuditSection (root sections only).
 *
 * ── DESIGN ───────────────────────────────────────────────────────────────────
 * This mapping only links TEMPLATE to ROOT sections (parentId = null).
 * The full subtree under each root section is retrieved via path queries.
 *
 * WHY ROOT-ONLY:
 *   The section tree is self-contained. Mapping every node would be redundant —
 *   if "A — Org Controls" is in a template, all its children are implicitly included.
 *   Templates compose by picking which root sections to include and in what order.
 *
 * REUSE:
 *   One root section subtree can appear in multiple templates with different orderNo.
 *   e.g. "Access Control" section (ISO 27001 A.9 tree) reused in both
 *        "ISO 27001 Full Audit" and "Access Control Spot Check" templates.
 */
@Entity
@Table(name = "audit_template_section_mappings",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_audit_tmpl_sec",
        columnNames = {"template_id", "section_id"}
    )
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditTemplateSectionMapping extends BaseEntity {

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** Must be a ROOT section (parentId = null) */
    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    /** Display order of this root section within the template */
    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;
}
