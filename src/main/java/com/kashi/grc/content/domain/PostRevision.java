package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A snapshot of a post's blocks and metadata at one save.
 *
 * Written only when the blocks actually changed, which matters because autosave
 * fires every two seconds while someone is typing. Hashing the blocks and
 * skipping identical saves is the difference between fifty useful revisions and
 * four thousand useless ones.
 *
 * Capped at 50 per post; the oldest are deleted beyond that. A cap rather than
 * unbounded history because the value of a revision decays fast and a @Lob per
 * save does not.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_post_revisions", indexes = {
        @Index(name = "ix_revision_post", columnList = "post_id, revision_number")
})
public class PostRevision extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "revision_number", nullable = false)
    private Integer revisionNumber;

    /** Full serialised Post state, not just blocks — a revert must restore the title too. */
    @Column(name = "snapshot_json", columnDefinition = "JSON")
    private String snapshotJson;

    @Column(name = "edited_by_id")
    private Long editedById;

    @Column(name = "note", length = 512)
    private String note;
}