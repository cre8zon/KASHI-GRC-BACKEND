package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Unpublished changes to a post that is already live.
 *
 * ── WHY A SEPARATE ROW AND NOT COLUMNS ON POST ───────────────────────────────
 *
 * There is one row per post, and the public API reads that row directly. So
 * while a post is PUBLISHED, its row IS the live article — typing into it
 * changes what readers see, and the build hook then ships a half-finished
 * sentence about ninety seconds later.
 *
 * Locking the post while live fixed the correctness problem and created a
 * usability one: fixing a typo meant taking the page down. This is the version
 * that does neither. Edits to a live post accumulate here; the live row is
 * untouched until someone deliberately releases them.
 *
 * ── WHY A JSON PAYLOAD RATHER THAN A MIRRORED SCHEMA ─────────────────────────
 *
 * Post has thirty-odd editable columns. Mirroring them here would mean two
 * schemas that must agree forever, and the failure mode when they drift is a
 * field that silently cannot be drafted — you edit it, it appears to save, and
 * publishing the draft quietly reverts it.
 *
 * The payload is a serialised PostUpsertRequest: the same object the editor
 * already sends and the same one applyEditableFields already knows how to
 * apply. Adding an editable field to Post requires no change here at all.
 *
 * ── ONE DRAFT PER POST ───────────────────────────────────────────────────────
 *
 * post_id is unique. Two people editing the same live article share one working
 * copy, which is the same thing that happens today on an unpublished post —
 * last write wins. Per-user drafts would need a merge story, and inventing one
 * nobody asked for is how this stops being shippable.
 */
@Entity
@Table(
        name = "content_post_drafts",
        uniqueConstraints = @UniqueConstraint(name = "uk_post_draft", columnNames = "post_id"),
        indexes = @Index(name = "idx_post_draft_post", columnList = "post_id")
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PostDraft extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private Long postId;

    /**
     * A serialised PostUpsertRequest holding only the fields that have been
     * edited since the post went live — not a full copy of the post.
     *
     * That distinction matters when the two diverge. If someone edits the live
     * article through another route, a draft holding only "title" applies only
     * the title and leaves the rest of their change intact; a draft holding a
     * whole snapshot would silently roll it back.
     */
    @Column(name = "payload_json", columnDefinition = "JSON")
    private String payloadJson;

    @Column(name = "updated_by_id")
    private Long updatedById;
}