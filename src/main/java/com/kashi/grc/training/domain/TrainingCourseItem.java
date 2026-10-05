package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One piece of content inside a course: a video, a document, a link or text.
 *
 * Separate from the course so a course can hold more than one video without a
 * schema change later, and so progress is tracked per item rather than as a
 * single percentage nobody can explain.
 *
 * ── documentId, NOT AN S3 KEY ─────────────────────────────────────────────
 * Content references documents.id. Upload goes through the platform's existing
 * three-step flow (useDocumentUpload: requestUpload, PUT to S3 with progress,
 * confirmUpload) — the same path evidence already uses — and playback through
 * /v1/documents/{id}/download-url.
 *
 * The earlier design stored a raw s3Key and asked a human to type it into a
 * form, which nothing in the browser could produce. Referencing a document also
 * means the video inherits the lifecycle, soft-delete and access control every
 * other file has, instead of being an object only this module knows about.
 *
 * s3Key is retained, unused, so the migration stays reversible. A later one
 * drops it.
 */
@Entity
@Table(name = "training_course_items", indexes = {
        @Index(name = "idx_tci_course", columnList = "course_id,sort_order"),
        @Index(name = "idx_tci_tenant", columnList = "tenant_id"),
        @Index(name = "idx_tci_document", columnList = "document_id"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingCourseItem extends GlobalOrTenantEntity {

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 30)
    @Builder.Default
    private ItemType itemType = ItemType.VIDEO;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** documents.id — the uploaded file. See class javadoc. */
    @Column(name = "document_id")
    private Long documentId;

    /** Retained, unused. Dropped in a later migration. */
    @Column(name = "s3_key", length = 500)
    private String s3Key;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /**
     * Seconds. Every completion calculation divides by this, so a VIDEO item
     * with a null duration cannot be published — TrainingCourseService.publish
     * refuses, rather than letting the course go live and silently mark
     * everyone complete on the first heartbeat.
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "external_url", length = 1000)
    private String externalUrl;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private boolean isRequired = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    public enum ItemType { VIDEO, DOCUMENT, LINK, TEXT }

    /** Only video is watch-tracked; the rest complete on acknowledgement. */
    @Transient
    public boolean isWatchTracked() { return itemType == ItemType.VIDEO; }
}