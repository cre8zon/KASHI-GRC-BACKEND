package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Join table linking AssessmentQuestion ↔ AssessmentQuestionOption.
 *
 * WHY THIS EXISTS:
 *   Previously options had question_id as an FK, meaning one option = one question.
 *   That caused duplication every time a question was reused.
 *   This join table decouples them: one option can be mapped to many questions.
 *
 * order_no: display order of this option within the question.
 */
@Entity
@Table(
        name = "question_option_mappings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_question_option",
                columnNames = {"question_id", "option_id"}
        )
)
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionOptionMapping extends BaseEntity {

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "option_id", nullable = false)
    private Long optionId;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;
}