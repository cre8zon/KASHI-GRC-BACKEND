package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One competitor, as structured data rather than prose.
 *
 * ── WHY THIS IS A TABLE AND NOT A TABLE BLOCK ────────────────────────────────
 * "KashiGRC vs Vanta", "KashiGRC vs Vanta vs Drata" and "Vanta alternatives"
 * all state Vanta's pricing. If that lives in an HTML table inside each post,
 * then Vanta changing its pricing means finding and editing three pages, and in
 * practice one of them stays wrong for a year — on a page whose entire claim is
 * that it is accurate and maintained.
 *
 * One record, rendered into many pages. Updating it updates all of them, and
 * lastVerifiedAt on this row is what the public "last verified" stamp reads.
 *
 * ── ON WRITING ABOUT COMPETITORS ─────────────────────────────────────────────
 * sourceUrl is required in spirit for every claim. A comparison page that cites
 * where each number came from is the one a skeptical buyer trusts, and it is
 * also the one that survives a competitor complaining. Prefer their own public
 * pricing page over a third-party aggregator.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_comparison_data", indexes = {
        @Index(name = "ix_comparison_slug", columnList = "slug", unique = true)
})
public class ComparisonData extends BaseEntity {

    @Column(name = "competitor_name", nullable = false, length = 255)
    private String competitorName;

    @Column(name = "slug", nullable = false, unique = true, length = 200)
    private String slug;

    @Column(name = "website_url", length = 512)
    private String websiteUrl;

    @Column(name = "logo_media_id")
    private Long logoMediaId;

    /** One line: what this product is, in their terms, stated fairly. */
    @Column(name = "positioning", length = 512)
    private String positioning;

    /**
     * [{ "name": "Starter", "price": "$X/yr", "note": "...", "sourceUrl": "..." }]
     * A list, because "starts at" is the number competitors publish and the
     * number that misleads.
     */
    @Column(name = "pricing_tiers_json", columnDefinition = "JSON")
    private String pricingTiersJson;

    /**
     * { "soc2": "full", "dpdp": "none", "auditorPortal": "partial" }
     *
     * Three states, not a boolean. A checkmark grid that can only say yes or no
     * forces you to claim a competitor cannot do something they partly can,
     * which is the fastest way to lose the reader you were trying to convince.
     */
    @Column(name = "feature_flags_json", columnDefinition = "JSON")
    private String featureFlagsJson;

    @Column(name = "g2_rating")
    private Double g2Rating;

    @Column(name = "g2_review_count")
    private Integer g2ReviewCount;

    @Column(name = "g2_url", length = 512)
    private String g2Url;

    /**
     * When a human last checked these facts against the source. Rendered
     * publicly. If it is more than a quarter old on a live comparison page,
     * ContentInsightsService flags it.
     */
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "last_verified_by_id")
    private Long lastVerifiedById;

    /** How the facts were gathered. Rendered as the methodology note. */
    @Column(name = "methodology_note", columnDefinition = "TEXT")
    private String methodologyNote;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}