package com.kashi.grc.trustcenter.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One tenant's public trust page.
 *
 * ── THE SLUG IS UNIQUE ACROSS THE PLATFORM, NOT THE TENANT ────────────────
 * Every other unique key in this codebase is (something, tenant_id), because
 * two tenants may both have a risk called RSK-001. A public URL is different:
 * /trust/acme can only belong to one organisation, so the constraint has no
 * tenant in it. Getting this wrong would let a second tenant claim a slug and
 * silently shadow the first.
 */
@Entity
@Table(
        name = "trust_centers",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_trust_slug",   columnNames = "slug"),
            @UniqueConstraint(name = "uq_trust_tenant", columnNames = "tenant_id"),
        })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TrustCenter extends AuditableEntity {

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)  private String headline;

    @Column(name = "intro_md", columnDefinition = "TEXT")
    private String introMd;

    @Column(name = "logo_document_id") private Long logoDocumentId;
    @Column(name = "primary_color", length = 20) private String primaryColor;
    @Column(name = "contact_email", length = 200) private String contactEmail;

    /**
     * Off by default, deliberately.
     *
     * A half-filled trust center published by accident is a public statement
     * about your security posture made before anybody reviewed it.
     */
    @Column(name = "is_published", nullable = false)
    @Builder.Default
    private boolean isPublished = false;

    @Column(name = "published_at") private LocalDateTime publishedAt;
    @Column(name = "published_by") private Long publishedBy;

    /** An email gate converts better; manual approval is what most security
     *  teams insist on for a pentest report. Both are legitimate. */
    @Column(name = "auto_approve_email_gated", nullable = false)
    @Builder.Default
    private boolean autoApproveEmailGated = false;

    @Column(name = "custom_domain", length = 200) private String customDomain;
}
