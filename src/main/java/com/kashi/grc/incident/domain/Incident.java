package com.kashi.grc.incident.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A security incident — ISO 27001 A.5.24-A.5.28, NIST SP 800-61 lifecycle.
 *
 * ── TWO CLOCKS, NOT ONE ───────────────────────────────────────────────────
 * occurredAt is when it started; detectedAt is when anyone noticed. Dwell time
 * is the gap, it is asked about in every post-incident review, and it cannot be
 * reconstructed later if only one was recorded.
 *
 * detectedAt is also the start of every REGULATORY clock. CERT-In's six hours
 * run from noticing, not from triage or confirmation, so an incident recorded
 * without detectedAt has no provable deadline.
 *
 * ── REGULATORY NOTIFICATIONS ARE ROWS, NOT COLUMNS ────────────────────────
 * applicableFrameworks holds framework_ref values — the same vocabulary as
 * common_control_mappings.framework_ref, not a new one. IncidentService syncs
 * one IncidentNotification row per listed framework, each carrying its own
 * due_at from regulatory_notification_rules. Adding a regime here is what
 * creates its clock.
 *
 * ── NO PLATFORM LIBRARY ───────────────────────────────────────────────────
 * Incidents are events, not a catalogue, so tenant_id is NOT NULL and
 * AuditableEntity applies — unlike Risk, which extends GlobalOrTenantEntity
 * because its library rows are global.
 */
@Entity
@Table(
        name = "incidents",
        indexes = {
                @Index(name = "idx_inc_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_inc_status",   columnList = "status"),
                @Index(name = "idx_inc_severity", columnList = "tenant_id,severity"),
                @Index(name = "idx_inc_owner",    columnList = "owner_id"),
                @Index(name = "idx_inc_detected", columnList = "detected_at"),
                @Index(name = "idx_inc_pii",      columnList = "personal_data_involved"),
                @Index(name = "idx_inc_sla",      columnList = "sla_breached"),
                @Index(name = "idx_inc_source",   columnList = "source_entity_type,source_entity_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Incident extends AuditableEntity {

    @Column(name = "incident_ref", length = 30)
    private String incidentRef;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "incident_type", length = 50)
    private String incidentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    @Builder.Default
    private Severity severity = Severity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.NEW;

    // ── Provenance ────────────────────────────────────────────────────────────

    /** Mirrors issues.source_module — SIEM, AUDIT, RISK, TPRM, SCAN. */
    @Column(name = "source_module", length = 50)
    private String sourceModule;

    @Column(name = "source_entity_type", length = 50)
    private String sourceEntityType;

    @Column(name = "source_entity_id")
    private Long sourceEntityId;

    @Column(name = "detection_source", length = 50)
    private String detectionSource;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    /** When it was NOTICED. Start of every regulatory clock. */
    @Column(name = "detected_at")
    private LocalDateTime detectedAt;

    @Column(name = "triaged_at")
    private LocalDateTime triagedAt;

    @Column(name = "contained_at")
    private LocalDateTime containedAt;

    @Column(name = "eradicated_at")
    private LocalDateTime eradicatedAt;

    @Column(name = "recovered_at")
    private LocalDateTime recoveredAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // ── Internal SLA ──────────────────────────────────────────────────────────

    @Column(name = "response_due_at")
    private LocalDateTime responseDueAt;

    @Column(name = "resolution_due_at")
    private LocalDateTime resolutionDueAt;

    @Column(name = "sla_breached", nullable = false)
    @Builder.Default
    private boolean slaBreached = false;

    /**
     * Incremented by a future escalation sweep. No sweep is wired yet, on
     * purpose: the Issues equivalent re-escalates every 24 hours with no cap
     * and grows the notifications table without bound. Incidents will get a
     * capped loop, or none until that is fixed.
     */
    @Column(name = "escalation_count", nullable = false)
    @Builder.Default
    private int escalationCount = 0;

    @Column(name = "last_escalated_at")
    private LocalDateTime lastEscalatedAt;

    // ── Regulatory applicability ──────────────────────────────────────────────

    /** Comma-separated framework_ref values. Drives IncidentNotification rows. */
    @Column(name = "applicable_frameworks", length = 500)
    private String applicableFrameworks;

    @Column(name = "personal_data_involved", nullable = false)
    @Builder.Default
    private boolean personalDataInvolved = false;

    @Column(name = "personal_data_categories", length = 500)
    private String personalDataCategories;

    @Column(name = "data_principals_affected")
    private Integer dataPrincipalsAffected;

    // ── Impact and response ───────────────────────────────────────────────────

    @Column(name = "impact_summary", columnDefinition = "TEXT")
    private String impactSummary;

    @Column(name = "affected_services", length = 500)
    private String affectedServices;

    @Column(name = "containment_actions", columnDefinition = "TEXT")
    private String containmentActions;

    @Column(name = "eradication_actions", columnDefinition = "TEXT")
    private String eradicationActions;

    @Column(name = "recovery_actions", columnDefinition = "TEXT")
    private String recoveryActions;

    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "lessons_learned", columnDefinition = "TEXT")
    private String lessonsLearned;

    @Column(name = "pir_completed_at")
    private LocalDateTime pirCompletedAt;

    // ── Ownership ─────────────────────────────────────────────────────────────

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_team", length = 200)
    private String ownerTeam;

    @Column(name = "reported_by_id")
    private Long reportedById;

    @Column(name = "control_tags", length = 500)
    private String controlTags;

    @Column(name = "framework_refs", length = 500)
    private String frameworkRefs;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Same vocabulary as Issue.Severity and the risk register, deliberately.
     * A third scale would make cross-module rollups meaningless on the
     * dashboard.
     */
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }

    /**
     * Must stay identical to module_blueprints.status_flow_json for INCIDENT
     * and to the allowed_statuses_json on each ui_actions row.
     *
     * There is no POST_INCIDENT_REVIEW status: a PIR is a document, not a
     * state. It is rootCause, lessonsLearned and pirCompletedAt, which keeps
     * the lifecycle linear.
     */
    public enum Status {
        NEW, TRIAGED, INVESTIGATING, CONTAINED, ERADICATED, RECOVERED, CLOSED, FALSE_POSITIVE
    }
}
