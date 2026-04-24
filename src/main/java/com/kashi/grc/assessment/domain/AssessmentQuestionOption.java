package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A reusable response option in the library.
 *
 * DESIGN: This entity has NO foreign key to AssessmentQuestion.
 * The option↔question relationship is managed via QuestionOptionMapping (join table).
 * One option can be linked to many questions — zero duplication.
 *
 * tenant_id = null  → global option created by Platform Admin
 * tenant_id = X     → private option created by org X
 */
@Entity
@Table(name = "assessment_question_options")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentQuestionOption extends GlobalOrTenantEntity {

    @Column(name = "option_value", nullable = false, length = 500)
    private String optionValue;

    @Column(name = "score")
    private Double score;
}