package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Primary grouping. One per post, and it appears in the breadcrumb, the URL of
 * the listing page, and the label above the headline.
 *
 * seoIntroCopy is not optional in spirit even though it is nullable: a category
 * page with a heading and a grid of cards is a thin page, and a thin page that
 * is linked from every article in the category is a thin page with authority
 * pointed at it. The intro copy is what makes /blog/category/soc-2 worth
 * ranking on its own.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_categories", indexes = {
        @Index(name = "ix_category_slug", columnList = "slug", unique = true)
})
public class ContentCategory extends BaseEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 200)
    private String slug;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "seo_intro_copy", columnDefinition = "TEXT")
    private String seoIntroCopy;

    @Column(name = "meta_title", length = 255)
    private String metaTitle;

    @Column(name = "meta_description", length = 320)
    private String metaDescription;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}