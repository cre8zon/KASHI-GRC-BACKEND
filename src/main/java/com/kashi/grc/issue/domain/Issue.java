package com.kashi.grc.issue.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * First-class Issue entity for enterprise issue management.
 *
 * Covers all three issue types as defined by the enterprise GRC reference:
 *   INTERNAL  — audit findings, control failures, self-assessments, policy violations
 *   EXTERNAL  — regulatory examination results, external audit findings, pen-test findings
 *   AUTOMATED — vulnerability scanner alerts, SIEM alerts, KRI threshold breaches
 *
 * WORKFLOW INTEGRATION:
 *   Each issue can have an active WorkflowInstance via workflowInstanceId.
 *   The workflow is started automatically by IssueService.create() and
 *   IssueIngestionService.ingest() using WorkflowEngineService.startWorkflow().
 *
 *   stepAction on the active step determines what the actor does:
 *     FILL       → RCA + remediation plan
 *     REVIEW     → triage / management response
 *     EVALUATE   → verification testing
 *     APPROVE    → closure sign-off
 *
 * UI KEYS (set automatically from issueType default or per-issue override):
 *   listScreenKey   = "issue_list"
 *   detailScreenKey = "issue_detail"
 *   itemScreenKey   = "issue_item_card"
 *
 * EXTERNAL DEDUPLICATION:
 *   externalId + externalSource form a unique key per tenant.
 *   Re-ingesting the same externalId updates the existing issue (idempotent).
 *
 * MIGRATION:
 *   See V20__issue_management.sql
 */
@Entity
@Table(
        name = "issues",
        indexes = {
                @Index(name = "idx_issue_tenant_status",  columnList = "tenant_id,status"),
                @Index(name = "idx_issue_type",            columnList = "tenant_id,issue_type"),
                @Index(name = "idx_issue_severity",        columnList = "tenant_id,severity"),
                @Index(name = "idx_issue_owner",           columnList = "owner_id"),
                @Index(name = "idx_issue_due",             columnList = "due_at"),
                @Index(name = "idx_issue_external",        columnList = "tenant_id,external_source,external_id"),
                @Index(name = "idx_issue_workflow",        columnList = "workflow_instance_id"),
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_issue_external",
                        columnNames = {"tenant_id", "external_source", "external_id"})
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Issue extends TenantAwareEntity {

    // ── Core identity ──────────────────────────────────────────────────────────

    /** Human-readable issue reference, e.g. ISS-2025-0042 */
    @Column(name = "issue_ref", length = 30)
    private String issueRef;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ── Classification ─────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 20)
    @Builder.Default
    private IssueType issueType = IssueType.INTERNAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    @Builder.Default
    private Severity severity = Severity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.OPEN;

    @Column(name = "category", length = 100)
    private String category; // INFORMATION_SECURITY, DATA_PRIVACY, ACCESS_CONTROL, etc.

    @Column(name = "source_module", length = 50)
    private String sourceModule; // TPRM, AUDIT, RISK, SCAN, SIEM, etc.

    // ── Source linkage ─────────────────────────────────────────────────────────

    @Column(name = "source_entity_type", length = 50)
    private String sourceEntityType; // ASSESSMENT, FINDING, CONTROL, RISK

    @Column(name = "source_entity_id")
    private Long sourceEntityId;

    /** Free-text source description for EXTERNAL and AUTOMATED types */
    @Column(name = "source_description", length = 500)
    private String sourceDescription;

    // ── External / automated deduplication ────────────────────────────────────

    /**
     * External system identifier for deduplication.
     * For AUTOMATED issues: CVE ID, Qualys finding ID, SIEM alert ID, etc.
     * Null for INTERNAL issues.
     */
    @Column(name = "external_id", length = 200)
    private String externalId;

    /**
     * Source system name for deduplication + routing.
     * Examples: "QUALYS", "TENABLE", "AWS_SECURITY_HUB", "SPLUNK", "CROWDSTRIKE"
     */
    @Column(name = "external_source", length = 50)
    private String externalSource;

    /** Raw payload from the external source, stored for audit and reprocessing */
    @Column(name = "external_payload", columnDefinition = "JSON")
    private String externalPayload;

    /** CVSS score for vulnerability-sourced issues (0.0–10.0) */
    @Column(name = "cvss_score")
    private Double cvssScore;

    // ── Ownership + escalation ─────────────────────────────────────────────────

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "raised_by_side", length = 20)
    private String raisedBySide; // ORGANIZATION, AUDITOR, SYSTEM

    // ── SLA + timing ──────────────────────────────────────────────────────────

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    /** When the issue was first acknowledged/triaged */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /** When remediation was marked complete */
    @Column(name = "remediated_at")
    private LocalDateTime remediatedAt;

    /** When the issue was formally closed */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    /**
     * Whether SLA has been breached. Set by IssueService.runSlaEscalation().
     * Triggers escalation notification when flipped from false → true.
     */
    @Column(name = "sla_breached", nullable = false)
    @Builder.Default
    private boolean slaBreached = false;

    /** Escalation count — incremented each time runSlaEscalation() escalates this issue */
    @Column(name = "escalation_count", nullable = false)
    @Builder.Default
    private int escalationCount = 0;

    @Column(name = "last_escalated_at")
    private LocalDateTime lastEscalatedAt;

    // ── Root cause analysis ────────────────────────────────────────────────────

    /**
     * Structured RCA data as JSON — populated via issue_rca_form screen key.
     * Schema: { "immediateCause": "...", "rootCause": "...",
     *           "contributingFactors": ["...", "..."],
     *           "isSystemic": true, "rcaMethod": "5WHY" }
     */
    @Column(name = "rca_json", columnDefinition = "JSON")
    private String rcaJson;

    @Column(name = "root_cause_category", length = 100)
    private String rootCauseCategory; // PROCESS_FAILURE, TECHNOLOGY, HUMAN_ERROR, THIRD_PARTY, etc.

    // ── Remediation ────────────────────────────────────────────────────────────

    @Column(name = "remediation_plan", columnDefinition = "TEXT")
    private String remediationPlan;

    @Column(name = "remediation_type", length = 30)
    private String remediationType; // CORRECTIVE, PREVENTIVE, COMPENSATING, ACCEPT_RISK

    @Column(name = "accepted_risk", nullable = false)
    @Builder.Default
    private boolean acceptedRisk = false;

    @Column(name = "accepted_risk_note", columnDefinition = "TEXT")
    private String acceptedRiskNote;

    @Column(name = "accepted_risk_by")
    private Long acceptedRiskBy;

    // ── Framework linkage ─────────────────────────────────────────────────────

    /** JSON array of linked control IDs: [42, 87, 103] */
    @Column(name = "linked_control_ids", columnDefinition = "JSON")
    private String linkedControlIds;

    /** JSON array of linked risk IDs */
    @Column(name = "linked_risk_ids", columnDefinition = "JSON")
    private String linkedRiskIds;

    /** Compliance framework reference e.g. "SOC2 CC6.1", "ISO 27001 A.8.1" */
    @Column(name = "framework_ref", length = 200)
    private String frameworkRef;

    // ── Workflow integration ───────────────────────────────────────────────────

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    // ── UI config ─────────────────────────────────────────────────────────────

    /** Screen Designer key for list view — defaults to "issue_list" */
    @Column(name = "list_screen_key", length = 100)
    @Builder.Default
    private String listScreenKey = "issue_list";

    /** Screen Designer key for detail view — defaults to "issue_detail" */
    @Column(name = "detail_screen_key", length = 100)
    @Builder.Default
    private String detailScreenKey = "issue_detail";

    /** Screen Designer key for item card in compound sections */
    @Column(name = "item_screen_key", length = 100)
    @Builder.Default
    private String itemScreenKey = "issue_item_card";

    // ── Enums ──────────────────────────────────────────────────────────────────

    public enum IssueType {
        INTERNAL,   // audit findings, self-assessments, control failures
        EXTERNAL,   // regulatory, external audit, pen-test, customer complaints
        AUTOMATED   // scanner alerts, SIEM, KRI breaches, continuous monitoring
    }

    public enum Severity {
        /** Active breach, material failure. Ack: 4h. Resolve: 72h. Escalate: CEO/Board */
        CRITICAL,
        /** Critical control failure. Ack: 24h. Resolve: 30d. Escalate: CRO */
        HIGH,
        /** Control weakness, minor gap. Ack: 72h. Resolve: 90d. Escalate: GRC Mgr */
        MEDIUM,
        /** Best-practice improvement. Ack: 5 days. Resolve: 180d. Escalate: Analyst */
        LOW
    }

    public enum Status {
        OPEN,
        TRIAGED,
        IN_PROGRESS,
        PENDING_REVIEW,
        PENDING_VALIDATION,
        RESOLVED,
        ACCEPTED_RISK,
        CLOSED,
        DUPLICATE
    }
}