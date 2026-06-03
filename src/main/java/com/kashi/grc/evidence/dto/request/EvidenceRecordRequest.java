package com.kashi.grc.evidence.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Request to create an EvidenceRecord.
 *
 * File upload itself goes through DocumentController (S3 presigned URL flow).
 * This request is called AFTER the file is uploaded to S3 — it registers the
 * evidence record and triggers EvidenceReuseEngine.propagate() asynchronously.
 *
 * FLOW:
 *   1. Frontend calls POST /v1/documents/upload → gets presigned URL + documentId
 *   2. Frontend uploads directly to S3 via presigned URL
 *   3. Frontend calls POST /v1/evidence with this request body, passing documentId
 *   4. EvidenceController creates EvidenceRecord
 *   5. EvidenceReuseEngine.propagate() fires async — links to all matching controls
 *
 * controlTag: the semantic tag that drives cross-module propagation.
 *   Must match *controlTagSnapshot or *questionTagSnapshot fields on entity instances.
 *   e.g. "ENCRYPTION_AT_REST", "MFA", "ACCESS_MGMT", "INFOSEC_POLICY"
 *   Leave null to upload evidence that is not auto-propagated (manual link only).
 *
 * sourceEntityType + sourceEntityId: the entity this was uploaded from.
 *   Context only — not used for matching. Used for "uploaded during ISO 27001
 *   audit control A.9.1.1" display in the evidence detail page.
 *   e.g. sourceEntityType="AUDIT_CONTROL_INSTANCE", sourceEntityId=42
 */
@Data
public class EvidenceRecordRequest {

    @NotBlank
    private String title;

    private String description;

    /**
     * Semantic tag for cross-module propagation.
     * Uppercase, underscore-separated. e.g. "ENCRYPTION_AT_REST"
     * Null = no auto-propagation, manual linking only.
     */
    private String controlTag;

    /** documentId from DocumentController after S3 upload */
    @NotBlank
    private String fileUrl;

    private String fileName;
    private Long   fileSizeBytes;
    private String mimeType;

    /** When this evidence is valid from (e.g. audit test date, cert issue date) */
    private LocalDateTime validFrom;

    /** When this evidence expires. Null = no expiry. */
    private LocalDateTime validUntil;

    /** Entity this was uploaded from — for display context only */
    private String sourceEntityType;
    private Long   sourceEntityId;
}
