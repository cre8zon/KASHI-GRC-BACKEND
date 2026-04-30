package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "question_comments")
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

    /**
     * Distinguishes informal user chat from system-generated audit events.
     * USER_COMMENT  — typed by a human (shown in Discussion thread).
     * SYSTEM_EVENT  — auto-logged by accept/override/revision actions (shown in Activity trail).
     * Null is treated as USER_COMMENT for backward compatibility with existing rows.
     */
    @Column(name = "comment_type", length = 30)
    @Builder.Default
    private String commentType = "USER_COMMENT";
}