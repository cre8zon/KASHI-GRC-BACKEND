package com.kashi.grc.content.dto;

import com.kashi.grc.content.domain.ContentEnums.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin-side shapes.
 *
 * Every field on PostUpsertRequest is nullable and null means "leave alone".
 * That is what makes autosave safe: the editor PUTs only what the user touched,
 * and a panel that was never opened cannot blank the fields inside it. A DTO
 * where null meant "clear" would have the SEO tab wipe the schema type every
 * time someone edited a paragraph.
 */
public final class PostDtos {

    private PostDtos() {}

    @Data
    public static class PostUpsertRequest {
        private String  title;
        private String  slug;
        private String  subtitle;
        private String  excerpt;
        /** Raw JSON array. Validated and normalised by BlockService on save. */
        private String  contentBlocks;
        private ContentType contentType;
        private Long    categoryId;
        private List<Long> tagIds;
        private Long    authorId;
        private Long    reviewedById;
        private Long    heroImageId;
        private Long    ogImageId;
        private String  metaTitle;
        private String  metaDescription;
        private String  canonicalUrl;
        private RobotsDirective robotsDirective;
        private String  focusKeyword;
        private SchemaType schemaType;
        private Long    pillarClusterId;
        private Integer clusterOrder;
        private String  definitionSummary;
        private String  comparisonDataIds;
        private Integer reviewIntervalMonths;
        /** Stamps lastVerifiedAt without touching content. The "I re-checked this" button. */
        private Boolean markVerified;
    }

    /** The admin list row. No blocks — a list of fifty posts must not ship fifty @Lobs. */
    @Data
    public static class PostListItem {
        private Long   id;
        private String slug;
        private String title;
        private ContentType contentType;
        private PostStatus  status;
        private String authorName;
        private String categoryName;
        private LocalDateTime publishedAt;
        private LocalDateTime contentUpdatedAt;
        private LocalDateTime lastVerifiedAt;
        private Integer readTimeMinutes;
        private Long    viewCount;
        private Long    helpfulYes;
        private Long    helpfulNo;
        private Integer inboundLinkCount;
        private RobotsDirective robotsDirective;
        /** True when review interval has elapsed. Drives the "needs re-verifying" chip. */
        private Boolean stale;
    }

    @Data
    public static class SlugCheckResponse {
        private String  slug;
        private boolean available;
        /** Set when the slug is free but is the source of an existing redirect. */
        private String  warning;
    }

    @Data
    public static class RevisionSummary {
        private Long    id;
        private Integer revisionNumber;
        private String  editedByName;
        private String  note;
        private LocalDateTime createdAt;
        private Integer wordCount;
    }

    /**
     * The on-page checklist shown beside the editor.
     *
     * Deliberately separate from PublishService.validate(): these are advisory
     * and never block. Mixing "you cannot publish without alt text" and "your
     * focus keyword is not in the first paragraph" into one list teaches editors
     * to ignore both.
     */
    @Data
    public static class SeoChecklistItem {
        private String  key;
        private String  label;
        private boolean passed;
        private String  detail;
        /** true when failing this also blocks publication. */
        private boolean blocking;
    }
}
