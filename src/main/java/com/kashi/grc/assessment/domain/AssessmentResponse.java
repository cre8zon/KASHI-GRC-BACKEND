package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "assessment_responses")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentResponse extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "question_instance_id", nullable = false)
    private Long questionInstanceId;

    @Column(name = "selected_option_instance_id")
    private Long selectedOptionInstanceId;

    @Column(name = "response_text", columnDefinition = "TEXT")
    private String responseText;

    @Column(name = "score_earned")
    private Double scoreEarned;

    @Column(name = "reviewer_status")
    @Builder.Default
    private String reviewerStatus = "PENDING";

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;
}