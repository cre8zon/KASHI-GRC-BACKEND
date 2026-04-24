package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Snapshot of an AssessmentQuestionOption at the moment a VendorAssessment is triggered.
 * Once created, NEVER updated.
 */
@Entity
@Table(name = "assessment_option_instances")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentOptionInstance extends BaseEntity {

    @Column(name = "question_instance_id", nullable = false)
    private Long questionInstanceId;

    /** Audit reference — never used for display */
    @Column(name = "original_option_id")
    private Long originalOptionId;

    @Column(name = "option_value", nullable = false, length = 500)
    private String optionValue;

    @Column(name = "score")
    private Double score;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;
}