package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One quiz attempt.
 *
 * EVERY attempt is kept, not just the passing one. "Passed on the fourth
 * attempt" is a materially different fact from "passed", and an auditor
 * sampling training effectiveness — or anyone asking whether the quiz is
 * actually teaching anything — is entitled to see which.
 *
 * answersJson stores what was submitted, so a disputed result can be
 * reconstructed rather than argued about.
 */
@Entity
@Table(name = "training_quiz_attempts", indexes = {
        @Index(name = "idx_tqa_assignment", columnList = "assignment_id,attempt_number"),
        @Index(name = "idx_tqa_tenant",     columnList = "tenant_id"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingQuizAttempt extends TenantAwareEntity {

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    @Column(name = "score_percent", nullable = false)
    @Builder.Default
    private Integer scorePercent = 0;

    @Column(name = "passed", nullable = false)
    @Builder.Default
    private boolean passed = false;

    @Column(name = "answers_json", columnDefinition = "JSON")
    private String answersJson;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
