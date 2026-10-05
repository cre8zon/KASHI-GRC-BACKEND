package com.kashi.grc.risk.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Join between a risk and the LIBRARY control that treats it.
 *
 * controlId references audit_controls.id — never audit_control_instances.id.
 * The distinction matters: a control instance belongs to one engagement and
 * expires with it, so linking to one would make "which controls treat this
 * risk" a question whose answer changes every audit cycle. The standing
 * control is the durable answer; effectiveness is read by traversing
 * audit_control_instances.original_control_id at query time.
 *
 * Zero-FK, consistent with the rest of the schema (see seed section 1).
 *
 * ── SCHEMA NOTE ────────────────────────────────────────────────────────────
 * The seed's CREATE TABLE omits updated_at. BaseEntity maps it with FIELD
 * access, so it cannot be un-mapped from a subclass — annotating an overridden
 * getter does nothing, because Hibernate never reads the getter. Every insert
 * would fail with "Unknown column 'updated_at' in 'field list'".
 *
 * risk_register_patch.sql adds the column (idempotently, and to the seed's own
 * DDL) rather than fighting the mapping. Adding a column is cheaper than
 * carving an exception into the entity hierarchy that every future reader has
 * to re-derive.
 */
@Entity
@Table(
        name = "risk_control_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_risk_control", columnNames = {"risk_id", "control_id"})
        },
        indexes = {
                @Index(name = "idx_rcl_risk",    columnList = "risk_id"),
                @Index(name = "idx_rcl_control", columnList = "control_id"),
                @Index(name = "idx_rcl_tenant",  columnList = "tenant_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RiskControlLink extends GlobalOrTenantEntity {

    @Column(name = "risk_id", nullable = false)
    private Long riskId;

    /** audit_controls.id — the library control, never an instance. */
    @Column(name = "control_id", nullable = false)
    private Long controlId;

    @Column(name = "link_note", length = 500)
    private String linkNote;

    @Column(name = "created_by")
    private Long createdBy;
}
