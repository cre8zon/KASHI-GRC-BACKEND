package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AuditPolicy — a compliance policy document in the library.
 *
 * ── WHAT IS A POLICY? ────────────────────────────────────────────────────────
 * A policy is a documented organisational commitment that satisfies control requirements.
 * One policy can satisfy MANY controls (many-to-many via AuditPolicyControlMapping).
 * One control can be satisfied by MANY policies.
 *
 * Examples:
 *   "Acceptable Use Policy"   → satisfies ISO A.8.1, SOC 2 CC6.1, NIST PR.AC-1
 *   "Encryption Policy"       → satisfies ISO A.10.1, SOC 2 CC6.7, GDPR Art.32
 *   "Incident Response Policy"→ satisfies ISO A.16.1, SOC 2 CC7.2, NIST RS.MA-1
 *
 * ── ISOLATION ────────────────────────────────────────────────────────────────
 * This is the LIBRARY entity — the source of truth for a policy.
 * AuditPolicyInstance is the RUNTIME snapshot — frozen at engagement creation.
 * Follows the identical isolation pattern as AuditControl → AuditControlInstance.
 *
 * ── CONTENT TYPES ────────────────────────────────────────────────────────────
 * RICH_TEXT    → content is stored in contentBody (rich text HTML/markdown)
 * PDF_UPLOAD   → policy is a PDF — evidenceRecordId links to EvidenceRecord
 * EXTERNAL_URL → policy lives in Confluence, SharePoint, etc. — externalUrl is set
 *
 * ── APPROVAL LIFECYCLE ───────────────────────────────────────────────────────
 * DRAFT → UNDER_REVIEW → APPROVED → DEPRECATED
 * Driven by workflowInstanceId — the POLICY workflow blueprint handles approvals.
 *
 * ── TAG SYSTEM ───────────────────────────────────────────────────────────────
 * controlTags: comma-separated list of control tags this policy relates to.
 * e.g. "ENCRYPTION_AT_REST,ENCRYPTION_IN_TRANSIT"
 * Used by EvidenceReuseEngine when the policy document itself is uploaded as evidence.
 * Also used to auto-suggest policy-control mappings.
 *
 * ── VERSION MANAGEMENT ───────────────────────────────────────────────────────
 * Each approval increments the version.
 * Old versions are NOT deleted — they become DEPRECATED.
 * previousVersionId links the version chain for audit trail.
 *
 * ── VANTA COMPARISON ─────────────────────────────────────────────────────────
 * Vanta's "Documents" tab = policies + certification docs + evidence files.
 * In KashiGRC:
 *   - AuditPolicy = structured policy with lifecycle, approval, control mapping
 *   - EvidenceRecord = uploaded file (PDF cert, screenshot, export)
 *   - The Documents tab shows BOTH — policies by type=POLICY, certs/evidence by type=FILE
 */
@Entity
@Table(name = "audit_policies",
        indexes = {
                @Index(name = "idx_ap_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_ap_status",  columnList = "status"),
                @Index(name = "idx_ap_owner",   columnList = "owner_id"),
                @Index(name = "idx_ap_review",  columnList = "next_review_date"),
                @Index(name = "idx_ap_tags",    columnList = "control_tags")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditPolicy extends GlobalOrTenantEntity {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "policy_ref", length = 30)
    private String policyRef;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** For APPROVED versions: previous version's ID for chain tracing */
    @Column(name = "previous_version_id")
    private Long previousVersionId;

    // ── Content ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    @Builder.Default
    private ContentType contentType = ContentType.RICH_TEXT;

    /** For RICH_TEXT policies: the policy text */
    @Column(name = "content_body", columnDefinition = "LONGTEXT")
    private String contentBody;

    /**
     * For PDF_UPLOAD policies: links to EvidenceRecord.id where the PDF lives.
     * Plain Long — no FK, follows zero-FK convention.
     */
    @Column(name = "evidence_record_id")
    private Long evidenceRecordId;

    /** For EXTERNAL_URL policies: Confluence, SharePoint, Google Docs URL */
    @Column(name = "external_url", columnDefinition = "TEXT")
    private String externalUrl;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PolicyStatus status = PolicyStatus.DRAFT;

    @Column(name = "approved_by_id")
    private Long approvedById;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "next_review_date")
    private LocalDate nextReviewDate;

    @Column(name = "review_frequency_months")
    @Builder.Default
    private Integer reviewFrequencyMonths = 12;

    // ── Ownership ─────────────────────────────────────────────────────────────

    /** The person accountable for this policy (e.g. CISO, Department Head) */
    @Column(name = "owner_id")
    private Long ownerId;

    /** Team or department responsible for maintaining the policy */
    @Column(name = "owner_team", length = 200)
    private String ownerTeam;

    // ── Control mapping ───────────────────────────────────────────────────────

    /**
     * Comma-separated control tags this policy relates to.
     * e.g. "ENCRYPTION_AT_REST,ENCRYPTION_IN_TRANSIT,KEY_MANAGEMENT"
     * Used for:
     *   1. Auto-suggesting policy-control mappings when controls share these tags
     *   2. EvidenceReuseEngine: when this policy doc is uploaded as evidence,
     *      auto-link to all controls with these tags
     */
    @Column(name = "control_tags", length = 500)
    private String controlTags;

    /** Framework references this policy is designed to satisfy */
    @Column(name = "framework_refs", length = 500)
    private String frameworkRefs;

    // ── Workflow ───────────────────────────────────────────────────────────────

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "created_by")
    private Long createdBy;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum ContentType {
        RICH_TEXT,    // content stored in contentBody
        PDF_UPLOAD,   // uploaded file — evidenceRecordId is set
        EXTERNAL_URL  // links to Confluence/SharePoint/Google Docs
    }

    public enum PolicyStatus {
        DRAFT,
        UNDER_REVIEW,
        APPROVED,
        DEPRECATED
    }
}