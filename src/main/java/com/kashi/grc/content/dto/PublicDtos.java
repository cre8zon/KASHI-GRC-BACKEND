package com.kashi.grc.content.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Public-site shapes. These field names are the contract between this module
 * and the prerenderer in the website repo — src/lib/content-api.js reads them
 * and scripts/prerender.mjs builds the sitemap from SitemapEntry. Renaming a
 * field here breaks a build over there, silently, at the next deploy.
 */
public final class PublicDtos {

    private PublicDtos() {}

    /** Card shape for listings. No blocks. */
    @Data
    public static class PostCard {
        private String slug;
        private String title;
        private String subtitle;
        private String excerpt;
        private String categoryName;
        private String categorySlug;
        private LocalDateTime publishedAt;
        private LocalDateTime contentUpdatedAt;
        private Integer readTimeMinutes;
        private String heroImageUrl;
        private String heroImageAlt;
        private AuthorRef author;
    }

    @Data
    public static class AuthorRef {
        private String slug;
        private String displayName;
        private String role;
        private String credentials;
        private String headshotUrl;
    }

    /** The full article. */
    @Data
    public static class PostDetail {
        private String slug;
        private String title;
        private String subtitle;
        private String excerpt;
        private String definitionSummary;

        /** Parsed, not a JSON string — the renderer walks this directly. */
        private Object contentBlocks;

        private String contentType;
        private LocalDateTime publishedAt;
        private LocalDateTime contentUpdatedAt;
        private LocalDateTime lastVerifiedAt;
        private Integer readTimeMinutes;
        private Integer wordCount;

        private String categoryName;
        private String categorySlug;
        private List<TagRef> tags;

        private AuthorRef author;
        private AuthorRef reviewedBy;

        private String heroImageUrl;
        private String heroImageAlt;
        private Integer heroImageWidth;
        private Integer heroImageHeight;
        private String ogImageUrl;

        private String metaTitle;
        private String metaDescription;
        private String canonicalUrl;
        private String robotsDirective;
        private String schemaType;

        private List<PostCard> relatedPosts;
        private PostCard previousInSeries;
        private PostCard nextInSeries;

        /** Populated for COMPARISON posts. */
        private List<ComparisonRow> comparisons;
    }

    @Data
    public static class TagRef {
        private String slug;
        private String name;
    }

    @Data
    public static class CategoryDetail {
        private String slug;
        private String name;
        private String description;
        private String seoIntroCopy;
        private String metaTitle;
        private String metaDescription;
        private Integer postCount;
    }

    @Data
    public static class AuthorDetail {
        private String slug;
        private String displayName;
        private String role;
        private String bio;
        private String credentials;
        private String headshotUrl;
        private String linkedinUrl;
        private String xUrl;
        private String websiteUrl;
        private List<PostCard> posts;
    }

    @Data
    public static class ComparisonRow {
        private String competitorName;
        private String slug;
        private String websiteUrl;
        private String logoUrl;
        private String positioning;
        private Object pricingTiers;
        private Object featureFlags;
        private Double g2Rating;
        private Integer g2ReviewCount;
        private LocalDateTime lastVerifiedAt;
        private String methodologyNote;
    }

    /**
     * One entry per URL the public site should prerender and list in the sitemap.
     *
     * postCount on a category or tag is not decoration — the prerenderer needs
     * it to know how many /page/N files to write. Omit it and only page one of
     * every listing gets built.
     */
    @Data
    public static class SitemapEntry {
        private String type;      // post | category | tag | author | pillar
        private String slug;
        private String path;
        private LocalDateTime lastmod;
        private Integer postCount;
    }

    @Data
    public static class SitemapData {
        private List<SitemapEntry> posts;
        private List<SitemapEntry> categories;
        private List<SitemapEntry> tags;
        private List<SitemapEntry> authors;
        private List<SitemapEntry> pillars;
    }

    @Data
    public static class RedirectRule {
        private String fromPath;
        private String toPath;
        private Integer statusCode;
    }
}
