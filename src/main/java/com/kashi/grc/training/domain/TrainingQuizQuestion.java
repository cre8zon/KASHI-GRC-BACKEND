package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/** One quiz question on a course. Options live in TrainingQuizOption. */
@Entity
@Table(name = "training_quiz_questions", indexes = {
        @Index(name = "idx_tqq_course", columnList = "course_id,sort_order"),
        @Index(name = "idx_tqq_tenant", columnList = "tenant_id"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingQuizQuestion extends GlobalOrTenantEntity {

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 30)
    @Builder.Default
    private QuestionType questionType = QuestionType.SINGLE_CHOICE;

    /** Shown after answering. This is where the learning actually happens. */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    public enum QuestionType { SINGLE_CHOICE, MULTI_CHOICE, TRUE_FALSE }
}
