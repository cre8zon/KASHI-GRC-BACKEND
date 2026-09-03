package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A 301 from a path that used to work to the path that works now.
 *
 * ── A PUBLISHED URL MUST NEVER 404 ───────────────────────────────────────────
 * Rows here are written automatically whenever a PUBLISHED post's slug changes.
 * Not offered, not suggested — written, in the same transaction as the slug
 * change, before it is saved. An article that has been live for six months has
 * inbound links and accumulated authority attached to its URL, and losing that
 * to a tidier slug is the most avoidable own goal in technical SEO.
 *
 * The public site fetches all active rows at build time and compiles them into
 * host redirect rules, so the 301 is served by the CDN and never reaches this
 * application.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_redirects", indexes = {
        @Index(name = "ix_redirect_from",   columnList = "from_path", unique = true),
        @Index(name = "ix_redirect_to",     columnList = "to_path"),
        @Index(name = "ix_redirect_active", columnList = "active")
})
public class ContentRedirect extends BaseEntity {

    @Column(name = "from_path", nullable = false, unique = true, length = 512)
    private String fromPath;

    @Column(name = "to_path", nullable = false, length = 512)
    private String toPath;

    @Column(name = "status_code", nullable = false)
    @Builder.Default
    private Integer statusCode = 301;

    /** "slug changed", "post archived", or whatever an editor typed. */
    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    /** Post.id this redirect was created for, where it was automatic. */
    @Column(name = "post_id")
    private Long postId;
}