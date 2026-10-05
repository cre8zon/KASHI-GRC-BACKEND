package com.kashi.grc.incident.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * The statutory reporting window for one regime.
 *
 * A platform row (tenantId NULL) ships the statutory window; a row with the
 * same frameworkRef and a tenantId overrides it for one organisation — for an
 * entity with a tighter contractual commitment than the law requires.
 *
 * ── dueHours NULL IS A REAL ANSWER ────────────────────────────────────────
 * SOC 2, ISO 27001 and the voluntary frameworks have no external reporting
 * clock. NULL means exactly that and must never be read as zero: the service
 * creates a notification row with no dueAt, so tagging an incident ISO27001
 * records applicability without inventing a deadline nobody owes.
 *
 * Extends BaseEntity rather than TenantAwareEntity because tenantId is
 * NULLABLE here — the platform rows are the point.
 */
@Entity
@Table(
        name = "regulatory_notification_rules",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_rule_framework_tenant",
                                  columnNames = {"framework_ref", "tenant_id"})
        },
        indexes = { @Index(name = "idx_rnr_tenant", columnList = "tenant_id") }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RegulatoryNotificationRule extends BaseEntity {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "framework_ref", nullable = false, length = 50)
    private String frameworkRef;

    @Column(name = "authority_name", nullable = false, length = 200)
    private String authorityName;

    /** Hours from the clock start. NULL = no fixed statutory window. */
    @Column(name = "due_hours")
    private Integer dueHours;

    @Column(name = "clock_start", nullable = false, length = 30)
    @Builder.Default
    private String clockStart = "DETECTION";

    /** The instrument the window comes from, so the next reader can check it. */
    @Column(name = "citation", length = 200)
    private String citation;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}
