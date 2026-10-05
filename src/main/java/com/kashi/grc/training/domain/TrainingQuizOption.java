package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One answer option.
 *
 * ── isCorrect NEVER LEAVES THE SERVER ─────────────────────────────────────
 * The learner-facing API returns option ids and text only. Grading happens in
 * TrainingAssignmentService against rows loaded fresh from the database. Any
 * DTO that carries isCorrect toward a browser is a bug: the quiz becomes
 * answerable from devtools in about four seconds, and every completion record
 * on the system stops being evidence of anything.
 */
@Entity
@Table(name = "training_quiz_options", indexes = {
        @Index(name = "idx_tqo_question", columnList = "question_id,sort_order"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingQuizOption extends BaseEntity {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "option_text", nullable = false, length = 1000)
    private String optionText;

    @Column(name = "is_correct", nullable = false)
    @Builder.Default
    private boolean isCorrect = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
