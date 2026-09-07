package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/** Secondary, flexible grouping. Many per post. */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_tags", indexes = {
        @Index(name = "ix_tag_slug", columnList = "slug", unique = true)
})
public class ContentTag extends BaseEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 200)
    private String slug;

    @Column(name = "description", length = 512)
    private String description;

    /**
     * Tag pages are indexable only once they have enough posts to be worth
     * indexing. Below that they are thin duplicates of the category page, so
     * the sitemap and the robots directive both read this.
     */
    @Column(name = "indexable", nullable = false)
    @Builder.Default
    private Boolean indexable = false;
}