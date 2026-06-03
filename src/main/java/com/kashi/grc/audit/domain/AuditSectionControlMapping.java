package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Join table: AuditSection ↔ AuditControl.
 *
 * Controls (leaf-level questions) attach at ANY depth of the section tree.
 *
 * ── EXAMPLES ─────────────────────────────────────────────────────────────────
 * ISO 27001 tree:
 *   A (depth=0)
 *    └── A.5 (depth=1)
 *         └── A.5.1 (depth=2)  ← controls attach here (A.5.1.1, A.5.1.2...)
 *
 * SOC 2 tree (shallower):
 *   CC6 (depth=0)
 *    └── CC6.1 (depth=1)       ← controls attach here directly
 *
 * Custom flat template:
 *   "Access Review" (depth=0)  ← controls attach directly to root section
 *
 * The framework imposes the depth — this mapping handles all cases uniformly.
 *
 * ── REUSE ────────────────────────────────────────────────────────────────────
 * One control can appear in many sections (same control in ISO 27001 and SOC 2).
 * weight / isMandatory / orderNo are per-section-context, not on the control itself.
 */
@Entity
@Table(name = "audit_section_control_mappings",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_audit_sec_ctrl",
        columnNames = {"section_id", "control_id"}
    )
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditSectionControlMapping extends BaseEntity {

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(name = "control_id", nullable = false)
    private Long controlId;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    @Column(name = "weight")
    private Double weight;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private boolean isMandatory = false;
}
