package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.content.domain.ContentEnums.LinkHealth;
import com.kashi.grc.content.domain.ContentEnums.LinkKind;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One link found in one post's blocks. Extracted on every save.
 *
 * ── WHY MATERIALISE THE LINK GRAPH ───────────────────────────────────────────
 * Two questions get asked repeatedly and neither can be answered by scanning
 * @Lob columns at request time:
 *
 *   "which published posts have nothing linking to them"  — the orphan report
 *   "which links on the site are broken"                  — the link checker
 *
 * Content teams answer the first with a spreadsheet and never answer the second.
 * Both are one indexed query once the graph is a table, and the graph is free
 * to build because the blocks are already being parsed on save for read time.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_post_links", indexes = {
        @Index(name = "ix_link_from",   columnList = "from_post_id"),
        @Index(name = "ix_link_to_post", columnList = "to_post_id"),
        @Index(name = "ix_link_health", columnList = "health")
})
public class PostLink extends BaseEntity {

    @Column(name = "from_post_id", nullable = false)
    private Long fromPostId;

    /** Resolved Post.id where the href pointed at an article. Null otherwise. */
    @Column(name = "to_post_id")
    private Long toPostId;

    @Column(name = "href", nullable = false, length = 1024)
    private String href;

    /** The link text, so the report can show what an orphan is missing. */
    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_kind", nullable = false, length = 20)
    private LinkKind linkKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "health", nullable = false, length = 20)
    @Builder.Default
    private LinkHealth health = LinkHealth.UNCHECKED;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "http_status")
    private Integer httpStatus;
}