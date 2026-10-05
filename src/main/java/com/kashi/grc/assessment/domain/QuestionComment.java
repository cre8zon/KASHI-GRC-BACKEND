package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One comment on one answer.
 *
 * ── visibility IS NEW, AND IT IS THE POINT OF THE THREE TABS ──────────────
 * Before migration 50 there was no such column, so every comment on a question
 * was readable by anyone who could read the question — vendor and assessing
 * organisation alike. "Vendor-only, org-only, common" was therefore not an
 * arrangement of existing data; it needed this field, and it needed it before
 * the tabs shipped, because a comment written without a side cannot be
 * classified afterwards without guessing what its author meant.
 *
 * Defaults to SHARED in the entity as well as in the column, and deliberately:
 * a write path that has not been updated produces a VISIBLE comment rather
 * than a silently hidden one. A comment nobody can see is far harder to notice
 * than one everybody can.
 *
 * Rows written before 50 have no value and are read as SHARED — which is not a
 * fallback but the truth about them. They were visible to both sides from the
 * day they were written, and recording anything else would be a claim about
 * who saw what that is false.
 *
 * ── THIS FIELD IS NOT SET FROM THE REQUEST ────────────────────────────────
 * AssessmentCommentService derives the author's side from their roles and
 * refuses a visibility they are not entitled to write. The value of the column
 * is that it cannot be got wrong, and that only holds if the server decides it.
 */
@Entity
@Table(name = "question_comments",
       indexes = @Index(name = "idx_qc_thread", columnList = "response_id,visibility,id"))
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionComment extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "response_id", nullable = false)
    private Long responseId;

    @Column(name = "comment_text", nullable = false, columnDefinition = "TEXT")
    private String commentText;

    @Column(name = "commented_by", nullable = false)
    private Long commentedBy;

    @Column(name = "comment_type", length = 30)
    @Builder.Default
    private String commentType = "USER_COMMENT";

    /** SHARED | ORG_ONLY | VENDOR_ONLY — see the class note. */
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private String visibility = "SHARED";
}
