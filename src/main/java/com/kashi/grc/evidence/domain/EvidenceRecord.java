package com.kashi.grc.evidence.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * EvidenceRecord — canonical evidence store supporting both MANUAL and AUTOMATED collection.
 *
 * collection_type discriminates two shapes:
 *
 *   MANUAL:    fileUrl + uploadedBy populated. rawPayload null.
 *              EvidenceLink → PENDING_REVIEW (human gate always).
 *
 *   AUTOMATED: integrationKey + rawPayload populated. fileUrl null.
 *              automationResult = PASS → EvidenceLink → AUTOMATION_VERIFIED (no gate).
 *              automationResult = FAIL → EvidenceLink → PENDING_REVIEW (auditor documents exception).
 *
 *   HYBRID:    Automation pulls raw data, human certifies.
 *              EvidenceLink → PENDING_REVIEW always.
 *
 * Tag-based propagation applies to all collection types — the EvidenceReuseEngine
 * fires after any record is created (manual upload or automated collection).
 */
@Entity
@Table(name = "evidence_records",
        indexes = {
                @Index(name = "idx_er_tenant",         columnList = "tenant_id"),
                @Index(name = "idx_er_control_tag",    columnList = "control_tag"),
                @Index(name = "idx_er_valid",          columnList = "valid_from, valid_until"),
                @Index(name = "idx_er_uploaded_by",    columnList = "uploaded_by"),
                @Index(name = "idx_er_collection_type",columnList = "collection_type"),
                @Index(name = "idx_er_integration_key",columnList = "integration_key")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class EvidenceRecord extends TenantAwareEntity {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Semantic compliance category — drives cross-module tag propagation */
    @Column(name = "control_tag", length = 80)
    private String controlTag;

    // ── Collection type ───────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_type", nullable = false, length = 10)
    @Builder.Default
    private CollectionType collectionType = CollectionType.MANUAL;

    // ── File storage (MANUAL / HYBRID) ────────────────────────────────────────

    /** S3 URL for file uploads. Null for pure AUTOMATED records. */
    @Column(name = "file_url", columnDefinition = "TEXT")
    private String fileUrl;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    // ── Automation fields (AUTOMATED / HYBRID) ────────────────────────────────

    /**
     * Integration check key — matches integration_checks.check_key.
     * e.g. 'OKTA_ADMIN_MFA', 'AWS_CLOUDTRAIL_ENABLED', 'GITHUB_BRANCH_PROTECTION'
     * Null for MANUAL records.
     */
    @Column(name = "integration_key", length = 100)
    private String integrationKey;

    /** Which IntegrationRun created this record — audit trail */
    @Column(name = "integration_run_id")
    private Long integrationRunId;

    /**
     * Full JSON API response from the integration.
     * The raw evidence payload — stored for auditor review and audit trail.
     * e.g. Okta /api/v1/users response filtered to admin users with MFA status.
     * Null for MANUAL records.
     */
    @Column(name = "raw_payload", columnDefinition = "LONGTEXT")
    private String rawPayload;

    /**
     * PASS — check passed, control is satisfied.
     * FAIL — check failed, auditor must document exception.
     * ERROR — integration error, re-run needed.
     * Null for MANUAL records.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "automation_result", length = 10)
    private AutomationResult automationResult;

    /**
     * Human-readable automation result summary.
     * e.g. "All 47 admin users have MFA enabled" (PASS)
     *      "3 users without MFA: alice@co.com, bob@co.com, carol@co.com" (FAIL)
     * Null for MANUAL records.
     */
    @Column(name = "automation_message", columnDefinition = "TEXT")
    private String automationMessage;

    /** When evidence was actually collected (integration run time vs upload time) */
    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

    /** How often this check re-runs. HOURLY | DAILY | WEEKLY | MONTHLY */
    @Column(name = "run_frequency", length = 10)
    private String runFrequency;

    /** When the integration runner should next collect this evidence */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    // ── Validity window ───────────────────────────────────────────────────────

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "is_expired", nullable = false)
    @Builder.Default
    private boolean expired = false;

    // ── Source context ────────────────────────────────────────────────────────

    @Column(name = "source_entity_type", length = 60)
    private String sourceEntityType;

    @Column(name = "source_entity_id")
    private Long sourceEntityId;

    // ── Audit ─────────────────────────────────────────────────────────────────

    /** userId for MANUAL; null for AUTOMATED */
    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    // ── Reuse tracking ────────────────────────────────────────────────────────

    @Column(name = "link_count")
    @Builder.Default
    private Integer linkCount = 0;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum CollectionType {
        MANUAL,     // human uploaded a file
        AUTOMATED,  // integration pulled and evaluated automatically
        HYBRID      // integration pulled raw data, human certifies
    }

    public enum AutomationResult {
        PASS,    // check passed → EvidenceLink.AUTOMATION_VERIFIED
        FAIL,    // check failed → EvidenceLink.PENDING_REVIEW
        ERROR,   // integration error — evidence not valid
        NOT_RUN  // scheduled but not yet run
    }
}