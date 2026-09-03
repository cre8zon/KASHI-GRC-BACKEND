package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Join row. A plain entity rather than a @ManyToMany because the zero-FK
 * convention rules out the association, and because a join table you own is a
 * join table you can query directly for the tag listing count.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_post_tags",
        uniqueConstraints = @UniqueConstraint(name = "ux_post_tag", columnNames = {"post_id", "tag_id"}),
        indexes = {
                @Index(name = "ix_post_tag_post", columnList = "post_id"),
                @Index(name = "ix_post_tag_tag",  columnList = "tag_id")
        })
public class PostTag extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;
}
