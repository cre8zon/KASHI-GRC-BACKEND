package com.kashi.grc.siteassistant.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A page of www.kashiguard.com that the website assistant may answer from.
 *
 * The website's built pages (product, pricing, frameworks, security…) are
 * pushed here by the website's own sync tool after a deploy
 * (tools/sync-assistant.mjs → POST /v1/site-assistant/sync/pages). Blog and
 * resource posts are NOT pages here: they are indexed straight from the
 * content platform when published (WEBSITE_POST).
 *
 * The row's id is the sourceId of its chunks (ChunkSourceType.WEBSITE_PAGE),
 * which is how an answer's citation finds its way back to a URL.
 */
@Entity
@Table(name = "site_chat_source_pages",
        uniqueConstraints = @UniqueConstraint(name = "uk_site_chat_source_page_path", columnNames = "path"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class SiteChatSourcePage extends BaseEntity {

    /** Site-relative path, e.g. /pricing. */
    @Column(name = "path", nullable = false, length = 400)
    private String path;

    @Column(name = "title", length = 300)
    private String title;

    /** SHA-256 of the text last indexed; unchanged pages are skipped. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    /** False once a full sync no longer contains the page; its chunks are retired. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
