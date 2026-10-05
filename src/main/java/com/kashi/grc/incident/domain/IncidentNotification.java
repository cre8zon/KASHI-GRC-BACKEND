package com.kashi.grc.incident.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One regulatory reporting obligation for one incident.
 *
 * frameworkRef uses the vocabulary that already exists in
 * common_control_mappings.framework_ref — CERTIN, DPDPA, RBIITG, RBIPSO,
 * RBIOUT, GDPR, HIPAA, PCIDSS, ISO27001, ISO27701, SOC2, NISTCSF, CISV8.
 * Not a second vocabulary invented for this module.
 *
 * ── dueAt IS WRITTEN ONCE ─────────────────────────────────────────────────
 * Computed from the incident's detectedAt plus the rule's dueHours at the
 * moment the row is created, then never recomputed. Editing a rule afterwards
 * must not silently move a deadline that has already been missed or met —
 * the whole evidential value of the record is that it says what the deadline
 * was at the time.
 *
 * Indexed on (tenant_id, notified_at, due_at) so "what is overdue right now"
 * is one query across every regime at once.
 */
@Entity
@Table(
        name = "incident_notifications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_incident_framework",
                                  columnNames = {"incident_id", "framework_ref"})
        },
        indexes = {
                @Index(name = "idx_in_incident",  columnList = "incident_id"),
                @Index(name = "idx_in_overdue",   columnList = "tenant_id,notified_at,due_at"),
                @Index(name = "idx_in_framework", columnList = "framework_ref"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentNotification extends TenantAwareEntity {

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "framework_ref", nullable = false, length = 50)
    private String frameworkRef;

    @Column(name = "authority_name", length = 200)
    private String authorityName;

    /** detectedAt + rule.dueHours, frozen at creation. Null when the rule has no clock. */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "notified_by")
    private Long notifiedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    /** Overdue = has a deadline, it has passed, and nothing was reported. */
    @Transient
    public boolean isOverdue() {
        return dueAt != null && notifiedAt == null && dueAt.isBefore(LocalDateTime.now());
    }
}
