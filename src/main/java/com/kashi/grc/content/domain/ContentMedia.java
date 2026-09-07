package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * An image in the content library.
 *
 * ── WHY NOT REUSE Document ───────────────────────────────────────────────────
 * The platform's Document entity extends TenantAwareEntity and carries
 * evidence-specific machinery — retention, supersession, expiry, document links.
 * Content images are platform-owned and have none of that. Sharing the table
 * would mean either putting a synthetic tenant on marketing assets or making
 * tenant nullable on evidence, and the second one is how evidence leaks.
 *
 * StorageService IS reused, which is where the actual value is: S3, SSE-KMS,
 * WebP conversion, presigned URLs and filename sanitisation all come for free.
 *
 * ── ALT TEXT IS NOT NULL ─────────────────────────────────────────────────────
 * Enforced by the column, not just by a validator, because "we will add alt text
 * later" is a promise no content team has ever kept. Publish is also blocked if
 * any image block references media without it — see PublishService.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_media", indexes = {
        @Index(name = "ix_media_uploaded_by", columnList = "uploaded_by_id"),
        @Index(name = "ix_media_s3key",       columnList = "s3_key")
})
public class ContentMedia extends BaseEntity {

    /** Public CDN URL. What the front end renders. */
    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    /** S3 object key, for deletion and re-processing. */
    @Column(name = "s3_key", length = 512)
    private String s3Key;

    @Column(name = "alt_text", nullable = false, length = 512)
    private String altText;

    /** Rendered under the image on the page; distinct from alt text, which is not. */
    @Column(name = "caption", length = 512)
    private String caption;

    @Column(name = "mime_type", length = 127)
    private String mimeType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /**
     * { "webp": "...", "avif": "...", "thumb": "..." }
     *
     * Width and height above are not decoration either — they go into the img
     * tag so the browser reserves space before the image loads. Without them
     * every article shifts as it renders, and CLS is one of the three Core Web
     * Vitals the whole exercise is being measured on.
     */
    @Column(name = "variants_json", columnDefinition = "JSON")
    private String variantsJson;

    @Column(name = "uploaded_by_id")
    private Long uploadedById;
}