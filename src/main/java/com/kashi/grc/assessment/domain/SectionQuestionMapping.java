package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Join table linking AssessmentSection ↔ AssessmentQuestion.
 *
 * WHY THIS EXISTS:
 *   Previously questions had section_id, weight, is_mandatory, order_no as columns.
 *   That meant every reuse required a copy of the question.
 *   This join table decouples them: one question can appear in many sections,
 *   each with its own weight, mandatory flag, and order.
 *
 * weight      — scoring weight for this question in this specific section context
 * isMandatory — whether vendors must answer this question in this section context
 * orderNo     — display order within the section
 */
@Entity
@Table(
        name = "section_question_mappings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_section_question",
                columnNames = {"section_id", "question_id"}
        )
)
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SectionQuestionMapping extends BaseEntity {

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    @Column(name = "weight")
    private Double weight;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private boolean isMandatory = false;
}