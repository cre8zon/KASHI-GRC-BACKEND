package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.content.domain.ContentEnums.ContentRole;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A byline with a profile page.
 *
 * ── WHY THIS IS NOT JUST User ────────────────────────────────────────────────
 * userId is nullable, because an external contributor — a partner auditor, a
 * customer CISO writing a guest piece — needs a byline and a credentials line
 * but must never get a login to the platform. Reusing User would force you to
 * create an account for every guest author, which is both a security decision
 * and a licensing one made for the wrong reason.
 *
 * Where userId IS set, the editorial role here governs publishing rights. It is
 * intentionally separate from platform RBAC: publishing a marketing article and
 * administering a tenant are different powers.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_authors", indexes = {
        @Index(name = "ix_author_slug", columnList = "slug", unique = true),
        @Index(name = "ix_author_user", columnList = "user_id")
})
public class ContentAuthor extends BaseEntity {

    /** User.id, or null for an external contributor with no login. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "slug", nullable = false, unique = true, length = 200)
    private String slug;

    /** "Head of Compliance Research" — shown in the byline. */
    @Column(name = "role", length = 255)
    private String role;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    /**
     * "CISA, ISO 27001 LA". Not decoration — for compliance content this is the
     * line that decides whether a skeptical reader keeps reading, and it is what
     * a reviewer line is worth quoting.
     */
    @Column(name = "credentials", length = 512)
    private String credentials;

    @Column(name = "headshot_media_id")
    private Long headshotMediaId;

    @Column(name = "linkedin_url", length = 512)
    private String linkedinUrl;

    @Column(name = "x_url", length = 512)
    private String xUrl;

    @Column(name = "website_url", length = 512)
    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_role", nullable = false, length = 30)
    @Builder.Default
    private ContentRole contentRole = ContentRole.CONTENT_AUTHOR;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}