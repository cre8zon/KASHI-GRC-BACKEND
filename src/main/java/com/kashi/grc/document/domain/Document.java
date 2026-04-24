package com.kashi.grc.document.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Document — the universal file record for the KashiGRC document system.
 *
 * All files (evidence uploads, generated reports, policies, contracts)
 * live in this table. Module-specific link tables are gone — everything
 * uses DocumentLink with entity_type + entity_id.
 *
 * Storage: AWS S3 (private bucket, SSE-KMS encryption, presigned URLs for access).
 * The s3_key field holds the full S3 object key. No file bytes in the DB.
 *
 * Versioning:
 *   Each upload is a new row. supersedes_id points to the previous version.
 *   When v2 is confirmed: old row status → SUPERSEDED.
 *   Frontend always queries for status='ACTIVE' to get the latest.
 *
 * Lifecycle states:
 *   PENDING    → presigned PUT URL issued, client hasn't uploaded yet
 *   ACTIVE     → client confirmed upload, S3 object exists and verified
 *   SUPERSEDED → a newer version replaced this one (kept for audit trail)
 *   DELETED    → soft-deleted (S3 object tagged, moved to Glacier by lifecycle rule)
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_docs_type_module", columnList = "document_type, source_module"),
        @Index(name = "idx_docs_tenant_type", columnList = "tenant_id, document_type"),
        @Index(name = "idx_docs_status",      columnList = "status"),
        @Index(name = "idx_docs_supersedes",  columnList = "supersedes_id"),
        @Index(name = "idx_docs_pending",     columnList = "created_at"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Document extends TenantAwareEntity {

    // ── File identity ──────────────────────────────────────────────────────

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** Human-readable title (e.g. "SOC 2 Report v2 - Acme Corp"). Auto-derived if null. */
    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "file_size")
    private Long fileSize;

    /** SHA-256 of the file bytes — for tamper detection */
    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "mime_type", length = 127)
    private String mimeType;

    /** MIME type before any conversion (e.g. image/jpeg before → image/webp) */
    @Column(name = "original_mime", length = 127)
    private String originalMime;

    // ── S3 storage ────────────────────────────────────────────────────────

    /** Full S3 object key: tenants/{tenantId}/{YYYY}/{MM}/{uuid}-{filename} */
    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    @Column(name = "s3_bucket", length = 255)
    private String s3Bucket;

    /** ETag returned by S3 after upload — confirms object integrity */
    @Column(name = "s3_etag", length = 255)
    private String s3Etag;

    /** Actual content length confirmed by S3 HeadObject (may differ from client claim) */
    @Column(name = "content_length")
    private Long contentLength;

    // ── Classification ────────────────────────────────────────────────────

    /**
     * EVIDENCE        — user-uploaded proof file (certificate, screenshot, policy PDF)
     * GENERATED_REPORT — system-produced report (assessment report, audit summary)
     * POLICY          — internal policy document
     * CONTRACT        — vendor contract / DPA
     * TEMPLATE        — assessment template export
     */
    @Column(name = "document_type", nullable = false, length = 30)
    @Builder.Default
    private String documentType = "EVIDENCE";

    /**
     * Populated for GENERATED_REPORT only.
     * Identifies which module produced this report:
     * VENDOR_ASSESSMENT | AUDIT | POLICY | RISK | VENDOR_CONTRACT
     * NULL for all user-uploaded documents.
     */
    @Column(name = "source_module", length = 60)
    private String sourceModule;

    /**
     * For GENERATED_REPORT: stores computed metadata so each module doesn't
     * need its own report table. Example:
     * {
     *   "compliance_pct": 82.5,
     *   "risk_rating": "MEDIUM",
     *   "open_remediation_count": 2,
     *   "trigger_event": "INITIAL" | "REMEDIATION_CLOSED" | "MANUAL",
     *   "total_earned_score": 247.5,
     *   "total_possible_score": 300.0,
     *   "remarks": "Auto-generated after all remediations resolved"
     * }
     */
    @Column(name = "generated_data", columnDefinition = "json")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> generatedData;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** PENDING | ACTIVE | SUPERSEDED | DELETED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /**
     * Points to the document this version replaces.
     * When v2 is uploaded: supersedes_id = v1.id, v1.status = SUPERSEDED.
     * NULL for all v1 documents (first upload).
     */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    /**
     * If this document was converted (JPEG → WebP), points to the original
     * raw upload document. NULL for all other documents.
     */
    @Column(name = "converted_from")
    private Long convertedFrom;

    /** Optional: when this evidence expires (e.g. quarterly cert). NULL = no expiry. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    /**
     * Backward-compat field (old filesystem path style).
     * New records use s3_key. Kept to avoid a breaking migration.
     * @deprecated Use s3Key instead.
     */
    @Deprecated
    @Column(name = "storage_path")
    private String storagePath;

    /**
     * SHA-256 hash used for legacy deduplication check.
     * New records use checksumSha256 for clarity.
     * @deprecated Use checksumSha256 instead.
     */
    @Deprecated
    @Column(name = "file_hash")
    private String fileHash;
}