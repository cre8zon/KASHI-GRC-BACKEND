package com.kashi.grc.comment.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Unified comment entity for tasks, assessments, and question responses.
 *
 * Extends TenantAwareEntity — gets id, tenantId, createdAt, updatedAt automatically.
 *
 * entity_type + entity_id = the item being commented on:
 *   TASK              → task_instance_id
 *   ASSESSMENT        → vendor_assessment_id
 *   QUESTION_RESPONSE → question_instance_id (industry standard anchor — not response_id)
 *
 * visibility controls who can read:
 *   ALL       → all workflow participants
 *   INTERNAL  → org side only (private notes, never visible to vendor)
 *   CISO_ONLY → vendor CISO + org side (remediation channel, not responders)
 */
@Entity
@Table(name = "entity_comments",
        indexes = {
                @Index(name = "idx_entity",   columnList = "entity_type,entity_id"),
                @Index(name = "idx_question", columnList = "question_instance_id"),
        })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EntityComment extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** For QUESTION_RESPONSE — always the question_instance_id (anchor point) */
    @Column(name = "question_instance_id")
    private Long questionInstanceId;

    /** Which response existed when commented — context/audit only */
    @Column(name = "response_id")
    private Long responseId;

    @Column(name = "comment_text", nullable = false, columnDefinition = "TEXT")
    private String commentText;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_type", nullable = false, length = 20)
    @Builder.Default
    private CommentType commentType = CommentType.COMMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 15)
    @Builder.Default
    private Visibility visibility = Visibility.ALL;

    /** For RESOLVED → links back to the REVISION_REQUEST comment */
    @Column(name = "parent_comment_id")
    private Long parentCommentId;

    /** Future @mentions — stored as JSON array of user IDs */
    @Column(name = "mentioned_user_ids", columnDefinition = "JSON")
    private String mentionedUserIds;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum EntityType {
        TASK,
        ASSESSMENT,
        QUESTION_RESPONSE
    }

    public enum CommentType {
        COMMENT,           // general note
        REVISION_REQUEST,  // responder/reviewer asks contributor to redo
        RESOLVED,          // closes a REVISION_REQUEST thread
        REMEDIATION,       // org→vendor CISO formal finding (CISO_ONLY visibility)
        SYSTEM             // auto-generated audit trail event
    }

    public enum Visibility {
        ALL,        // all workflow participants
        INTERNAL,   // org side only
        CISO_ONLY   // vendor CISO + org side (not responders/contributors)
    }
}