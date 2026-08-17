package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * AuditFinding — a documented deficiency, gap, or observation raised during an engagement.
 *
 * Lifecycle:  OPEN → IN_REMEDIATION → PENDING_VALIDATION → CLOSED
 *             OPEN → ACCEPTED_RISK  (risk accepted, no remediation)
 *
 * A finding is linked to a specific AuditControlInstance (the control that failed).
 * It may optionally be escalated to an Issue (Issue module) via linkedIssueId.
 *
 * findingRef: auto-generated e.g. FND-2026-0042
 *
 * ddl-auto=update creates the table on next restart — no migration file needed.
 */
@Entity
@Table(name = "audit_findings",
        indexes = {
                @Index(name = "idx_finding_engagement",   columnList = "engagement_id"),
                @Index(name = "idx_finding_control_inst", columnList = "control_instance_id"),
                @Index(name = "idx_finding_status",       columnList = "status"),
                @Index(name = "idx_finding_tenant",       columnList = "tenant_id"),
                @Index(name = "idx_finding_linked_issue", columnList = "linked_issue_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditFinding extends TenantAwareEntity {

    // ── Reference & Identity ──────────────────────────────────────────────────

    @Column(name = "finding_ref", length = 30)
    private String findingRef;             // e.g. FND-2026-0042

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    @Column(name = "control_instance_id")
    private Long controlInstanceId;        // which control raised this finding

    // ── Content ───────────────────────────────────────────────────────────────

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "auditor_notes", columnDefinition = "TEXT")
    private String auditorNotes;

    // ── Classification ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    @Builder.Default
    private Severity severity = Severity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 40)
    @Builder.Default
    private FindingType findingType = FindingType.CONTROL_DEFICIENCY;

    /**
     * How this finding came to exist.
     *
     * An AUTOMATED finding with no linked issue means the runner raised it and
     * escalation could not complete — worth surfacing. A MANUAL one with no
     * issue simply has not been escalated yet, which is normal. Without this the
     * two are indistinguishable on screen.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private Source source = Source.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.OPEN;

    @Column(name = "framework_ref", length = 100)
    private String frameworkRef;

    @Column(name = "control_ref_snapshot", length = 100)
    private String controlRefSnapshot;     // snapshot of the control's ref at finding creation

    // ── Ownership & Dates ─────────────────────────────────────────────────────

    @Column(name = "raised_by", nullable = false)
    private Long raisedBy;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "raised_at", nullable = false)
    @Builder.Default
    private LocalDateTime raisedAt = LocalDateTime.now();

    // ── Remediation ───────────────────────────────────────────────────────────

    @Column(name = "remediation_plan", columnDefinition = "TEXT")
    private String remediationPlan;

    @Column(name = "remediation_type", length = 30)
    private String remediationType;       // CORRECTIVE | PREVENTIVE | COMPENSATING

    @Column(name = "remediation_started_at")
    private LocalDateTime remediationStartedAt;

    @Column(name = "remediated_at")
    private LocalDateTime remediatedAt;

    @Column(name = "remediated_by")
    private Long remediatedBy;

    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    @Column(name = "validated_by")
    private Long validatedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    // ── Withdrawal ────────────────────────────────────────────────────────
    // Separate from closure on purpose: a withdrawn finding was never valid,
    // and an auditor reading history has to be able to tell the difference
    // between "we fixed it" and "we should not have raised it".

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "withdrawn_by")
    private Long withdrawnBy;

    @Column(name = "withdrawal_reason", columnDefinition = "TEXT")
    private String withdrawalReason;

    // ── Risk acceptance ───────────────────────────────────────────────────────

    @Column(name = "accepted_risk", nullable = false)
    @Builder.Default
    private boolean acceptedRisk = false;

    @Column(name = "accepted_risk_note", columnDefinition = "TEXT")
    private String acceptedRiskNote;

    @Column(name = "accepted_risk_by")
    private Long acceptedRiskBy;

    @Column(name = "accepted_risk_at")
    private LocalDateTime acceptedRiskAt;

    // ── Issue escalation ──────────────────────────────────────────────────────

    /** When finding is escalated to the Issue module, the Issue ID is stored here. */
    @Column(name = "linked_issue_id")
    private Long linkedIssueId;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, INFORMATIONAL
    }

    public enum FindingType {
        CONTROL_DEFICIENCY,
        MATERIAL_WEAKNESS,
        SIGNIFICANT_DEFICIENCY,
        OBSERVATION,
        BEST_PRACTICE
    }

    /** MANUAL — an auditor raised it. AUTOMATED — the integration runner did. */
    public enum Source {
        MANUAL,
        AUTOMATED
    }

    public enum Status {
        OPEN,
        IN_REMEDIATION,
        PENDING_VALIDATION,
        CLOSED,
        ACCEPTED_RISK,
        /**
         * The finding should never have been raised — the underlying test result
         * was recorded in error and has been superseded.
         *
         * Distinct from CLOSED, which means "the problem was real and has been
         * fixed". Conflating the two would let anyone erase a genuine finding by
         * re-recording the test as PASS, which is exactly the silent mutation
         * this status exists to replace.
         *
         * Terminal: cascadeDeriveControlResults and reviewPolicy must treat it
         * like CLOSED when deciding whether an open finding already exists, or
         * the next derive raises a duplicate.
         */
        WITHDRAWN
    }
}