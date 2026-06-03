package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AuditPolicyInstance — 100% isolated runtime snapshot of one AuditPolicy per engagement.
 *
 * ── FULL ISOLATION STACK ─────────────────────────────────────────────────────
 *
 *   AuditPolicy (library, mutable — can be updated, re-approved, deprecated)
 *       ↓ snapshotted at engagement creation time
 *   AuditPolicyInstance (THIS ENTITY — frozen)
 *
 * WHY SNAPSHOT POLICIES? ──────────────────────────────────────────────────────
 * Audit evidence is point-in-time. If a policy is updated DURING an engagement,
 * the auditor needs to evaluate the policy as it was at fieldwork start, not
 * the current version. The instance captures the exact policy state the auditor reviewed.
 *
 * Example:
 *   Engagement starts 1 June 2026. Encryption Policy v2.3 is snapshotted.
 *   On 15 June, org updates to v2.4 (new key rotation requirement).
 *   The engagement still references v2.3 — auditor's evaluation is valid.
 *   The NEXT engagement (2027 cycle) will snapshot v2.4.
 *
 * ── ZERO FK RULE ─────────────────────────────────────────────────────────────
 * originalPolicyId = plain Long, audit trail only.
 * engagementId     = plain Long, NOT a @ManyToOne.
 *
 * ── AUDITOR REVIEW ───────────────────────────────────────────────────────────
 * The auditor reviews each policy instance and marks it as REVIEWED or EXCEPTION.
 * reviewResult captures their professional conclusion about this policy.
 */
@Entity
@Table(name = "audit_policy_instances",
    indexes = {
        @Index(name = "idx_api_engagement", columnList = "engagement_id"),
        @Index(name = "idx_api_tenant",     columnList = "tenant_id"),
        @Index(name = "idx_api_original",   columnList = "original_policy_id"),
        @Index(name = "idx_api_status",     columnList = "review_result")
    }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditPolicyInstance extends TenantAwareEntity {

    // ── Scope ─────────────────────────────────────────────────────────────────

    @Column(name = "original_policy_id", nullable = false)
    private Long originalPolicyId;

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    // ── Policy snapshots ──────────────────────────────────────────────────────

    @Column(name = "title_snapshot", nullable = false, length = 500)
    private String titleSnapshot;

    @Column(name = "policy_ref_snapshot", length = 30)
    private String policyRefSnapshot;

    @Column(name = "version_snapshot", nullable = false)
    private Integer versionSnapshot;

    @Column(name = "description_snapshot", columnDefinition = "TEXT")
    private String descriptionSnapshot;

    @Column(name = "content_type_snapshot", length = 20)
    private String contentTypeSnapshot;

    /**
     * For RICH_TEXT: the actual policy body at snapshot time.
     * For PDF_UPLOAD: null — evidenceRecordIdSnapshot links to the file.
     * For EXTERNAL_URL: null — externalUrlSnapshot has the link.
     */
    @Column(name = "content_body_snapshot", columnDefinition = "LONGTEXT")
    private String contentBodySnapshot;

    @Column(name = "evidence_record_id_snapshot")
    private Long evidenceRecordIdSnapshot;

    @Column(name = "external_url_snapshot", columnDefinition = "TEXT")
    private String externalUrlSnapshot;

    @Column(name = "owner_id_snapshot")
    private Long ownerIdSnapshot;

    @Column(name = "approved_at_snapshot")
    private LocalDateTime approvedAtSnapshot;

    @Column(name = "effective_date_snapshot")
    private LocalDate effectiveDateSnapshot;

    @Column(name = "next_review_date_snapshot")
    private LocalDate nextReviewDateSnapshot;

    @Column(name = "policy_status_snapshot", length = 20)
    private String policyStatusSnapshot;  // APPROVED, DRAFT, DEPRECATED etc.

    @Column(name = "control_tags_snapshot", length = 500)
    private String controlTagsSnapshot;

    @Column(name = "framework_refs_snapshot", length = 500)
    private String frameworkRefsSnapshot;

    @Column(name = "snapshotted_at", nullable = false)
    private LocalDateTime snapshottedAt;

    // ── Auditor review ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "review_result", length = 20)
    @Builder.Default
    private ReviewResult reviewResult = ReviewResult.NOT_REVIEWED;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "auditor_notes", columnDefinition = "TEXT")
    private String auditorNotes;

    public enum ReviewResult {
        NOT_REVIEWED,
        ADEQUATE,        // policy adequately addresses the control requirement
        ADEQUATE_WITH_GAPS, // policy is adequate but has minor gaps noted
        INADEQUATE,      // policy does not adequately address the requirement → finding
        NOT_APPLICABLE   // policy is out of scope for this control
    }
}
