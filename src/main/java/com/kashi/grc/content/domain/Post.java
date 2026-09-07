package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.content.domain.ContentEnums.ContentType;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.ContentEnums.RobotsDirective;
import com.kashi.grc.content.domain.ContentEnums.SchemaType;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One piece of published content, of any type.
 *
 * ── NOT TENANT-AWARE, AND THAT IS THE POINT ──────────────────────────────────
 * This extends BaseEntity, not TenantAwareEntity. Content is platform-owned:
 * there is one blog, at www.digiosec.com, written by us. Adding tenant_id would
 * buy nothing and cost three things that matter — slugs would need per-tenant
 * namespacing, every public query would need a scope it does not have (the
 * public site has no session), and the sitemap would have to decide whose
 * content it lists.
 *
 * The author is still a real person with a real profile page, because author
 * E-E-A-T is a genuine ranking factor for compliance content. That is what
 * ContentAuthor is for; it is not a tenant concept.
 *
 * ── ZERO-FK ──────────────────────────────────────────────────────────────────
 * Relationships are plain Long columns, following the platform convention. No
 * @ManyToOne, no lazy-loading surprises in a public endpoint that must be fast
 * and cacheable.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_posts", indexes = {
        @Index(name = "ix_post_slug",      columnList = "slug", unique = true),
        @Index(name = "ix_post_status",    columnList = "status"),
        @Index(name = "ix_post_type",      columnList = "content_type"),
        @Index(name = "ix_post_category",  columnList = "category_id"),
        @Index(name = "ix_post_author",    columnList = "author_id"),
        @Index(name = "ix_post_published", columnList = "published_at"),
        @Index(name = "ix_post_pillar",    columnList = "pillar_cluster_id"),
        @Index(name = "ix_post_scheduled", columnList = "status, scheduled_for")
})
public class Post extends BaseEntity {

    /**
     * The URL. Unique across the whole platform, editable, and independent of
     * the title — renaming an article must not break its URL, and fixing a typo
     * in a headline must not either.
     */
    @Column(name = "slug", nullable = false, unique = true, length = 200)
    private String slug;

    /**
     * True once a human has typed a slug, after which it stops following the
     * title.
     *
     * The old behaviour generated the slug once, at create, from whatever the
     * title was — which is "Untitled". Retitling never touched it, so a
     * finished draft sat at /blog/untitled-2 and the only fix was noticing and
     * editing by hand.
     *
     * Auto-following forever is the other wrong answer: after publication a
     * slug change costs a redirect and leaks a little authority at every hop,
     * so a typo fix in a headline must not silently move a live URL. Following
     * until first publish or first manual edit, whichever comes first, is the
     * behaviour that is right in both halves of a post's life.
     */
    @Builder.Default
    @Column(name = "slug_locked", nullable = false)
    private Boolean slugLocked = false;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    /** The dek — one sentence expanding the headline's promise. */
    @Column(name = "subtitle", length = 512)
    private String subtitle;

    @Column(name = "excerpt", columnDefinition = "TEXT")
    private String excerpt;

    /**
     * The article body: an ordered JSON array of typed blocks.
     *
     * Stored as JSON rather than HTML because the same block has to render three
     * ways — as HTML on the public site, as an editable component in the admin,
     * and as structured data in JSON-LD. An `faq` block becomes an accordion
     * AND a FAQPage schema entry; you cannot recover that from a <div>.
     *
     * See BlockService for the schema and BlockService.textOf for how the read
     * time, the word count and the search index all derive from it.
     */
    @Column(name = "content_blocks", columnDefinition = "JSON")
    private String contentBlocks;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    @Builder.Default
    private ContentType contentType = ContentType.BLOG;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PostStatus status = PostStatus.DRAFT;

    // ── people (zero-FK) ─────────────────────────────────────────────────────

    /** ContentAuthor.id — the byline, and the author page every post links to. */
    @Column(name = "author_id")
    private Long authorId;

    /**
     * ContentAuthor.id of the subject-matter reviewer.
     *
     * Renders as "Reviewed by X, CISA" above the fold. This exists because the
     * buyer for compliance content is trained to distrust marketing copy, and a
     * named reviewer with credentials is the single cheapest trust signal
     * available. It is also what the biggest players in this category use.
     */
    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    /** User.id of whoever last saved. Not the byline — the audit trail. */
    @Column(name = "last_edited_by_id")
    private Long lastEditedById;

    // ── taxonomy ─────────────────────────────────────────────────────────────

    @Column(name = "category_id")
    private Long categoryId;

    /**
     * Post.id of the pillar/hub page this post belongs to. Self-referencing.
     * Drives "related in this series", prev/next within the cluster, and the
     * hub page's own listing — one column doing the work of a join table
     * because a post belongs to at most one cluster.
     */
    @Column(name = "pillar_cluster_id")
    private Long pillarClusterId;

    /** Position within the cluster, for prev/next. Null sorts last. */
    @Column(name = "cluster_order")
    private Integer clusterOrder;

    // ── media ────────────────────────────────────────────────────────────────

    @Column(name = "hero_image_id")
    private Long heroImageId;

    /**
     * Deliberately separate from the hero. Social crops are 1.91:1 and a hero
     * built for a 3:1 content column loses its subject when a platform crops it.
     */
    @Column(name = "og_image_id")
    private Long ogImageId;

    // ── dates ────────────────────────────────────────────────────────────────

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "scheduled_for")
    private LocalDateTime scheduledFor;

    /**
     * The "Last updated" stamp, and dateModified in JSON-LD.
     *
     * Distinct from BaseEntity.updatedAt on purpose. updatedAt moves on every
     * save. This moves ONLY when a content block actually changed — compared by
     * hash in PostService. Fixing a typo in the meta description must not
     * advertise the article as freshly updated, because a freshness signal that
     * fires on every save is a freshness signal that means nothing.
     */
    @Column(name = "content_updated_at")
    private LocalDateTime contentUpdatedAt;

    /**
     * When the facts in this post were last checked, as opposed to edited.
     *
     * Comparison pages carry competitor pricing and feature claims that rot
     * without anyone touching the file. Rendered as "we re-verify this
     * quarterly, last checked X" — trust through transparency about
     * maintenance rather than a claim of accuracy.
     */
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    /** Months after which this post is flagged for re-verification. Null = never. */
    @Column(name = "review_interval_months")
    private Integer reviewIntervalMonths;

    // ── SEO ──────────────────────────────────────────────────────────────────

    @Column(name = "meta_title", length = 255)
    private String metaTitle;

    @Column(name = "meta_description", length = 320)
    private String metaDescription;

    /** Self-referencing by default; set only to point elsewhere. */
    @Column(name = "canonical_url", length = 512)
    private String canonicalUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "robots_directive", nullable = false, length = 20)
    @Builder.Default
    private RobotsDirective robotsDirective = RobotsDirective.INDEX_FOLLOW;

    @Column(name = "focus_keyword", length = 255)
    private String focusKeyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "schema_type", length = 20)
    private SchemaType schemaType;

    // ── derived, recomputed on save ──────────────────────────────────────────

    @Column(name = "read_time_minutes")
    private Integer readTimeMinutes;

    @Column(name = "word_count")
    private Integer wordCount;

    /**
     * SHA-256 of the normalised block array. The only thing that decides whether
     * contentUpdatedAt moves and whether a revision is written.
     */
    @Column(name = "blocks_hash", length = 64)
    private String blocksHash;

    // ── counters ─────────────────────────────────────────────────────────────

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Long viewCount = 0L;

    @Column(name = "helpful_yes", nullable = false)
    @Builder.Default
    private Long helpfulYes = 0L;

    @Column(name = "helpful_no", nullable = false)
    @Builder.Default
    private Long helpfulNo = 0L;

    /**
     * Denormalised count of inbound internal links, maintained by
     * ContentInsightsService. A published post with zero of these is an orphan:
     * it exists, it is in the sitemap, and no page on the site points at it,
     * which is close to the worst possible state for a piece of content.
     */
    @Column(name = "inbound_link_count", nullable = false)
    @Builder.Default
    private Integer inboundLinkCount = 0;

    // ── comparison (Phase 3) ─────────────────────────────────────────────────

    /**
     * Comma-separated ComparisonData ids for a COMPARISON post, in display
     * order. Zero-FK, and a list rather than one id because buyers shortlist
     * three tools, not two — "KashiGRC vs Vanta vs Drata" has to be the same
     * page type as the two-way version, not a special case.
     */
    @Column(name = "comparison_data_ids", length = 512)
    private String comparisonDataIds;

    // ── glossary (Phase 3) ───────────────────────────────────────────────────

    /**
     * The one-or-two-sentence answer, rendered before anything else on a
     * GLOSSARY page and emitted as DefinedTerm.description. Kept out of the
     * blocks because featured-snippet capture depends on it being first and
     * self-contained, and an editor should not be able to bury it.
     */
    @Column(name = "definition_summary", columnDefinition = "TEXT")
    private String definitionSummary;

    /**
     * Not a column — populated on read so the editor knows which tags are on.
     *
     * Tags live in content_post_tags and the zero-FK convention rules out a
     * @ManyToMany, so nothing about them was reaching the admin. The result was
     * not a cosmetic bug: SettingsPanel read post.tagIds, always got undefined,
     * and so every tag click sent an array containing ONLY the tag just
     * clicked — silently replacing the whole set. Two tags in, one tag out.
     */
    @Transient
    private java.util.List<Long> tagIds;

    /**
     * Set only on the editor's view of a live post that has a working copy.
     *
     * Transient, like tagIds — the flag is derived from whether a row exists in
     * content_post_drafts, and storing it on the post would be a second copy of
     * that truth waiting to disagree with the first.
     */
    @Transient
    private Boolean hasUnpublishedChanges;
}