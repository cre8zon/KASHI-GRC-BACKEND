package com.kashi.grc.assessment.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Tracks when a review assistant submits their evaluations for a section.
 * Mirrors ContributorSectionSubmission exactly — one row per (assistant_user_id, section_instance_id).
 *
 * Used to:
 *  - Lock assistant's eval for that section (read-only after submit)
 *  - Determine when ALL assistant's sections are submitted → auto-approve sub-task
 *  - Audit trail: who submitted what and when
 *
 * taskInstanceId is nullable: revision/clarification flows may have no active task.
 */
@Entity
@Table(name = "reviewer_assistant_section_submissions",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"section_instance_id", "assistant_user_id"}))
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewerAssistantSectionSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "section_instance_id", nullable = false)
    private Long sectionInstanceId;

    @Column(name = "assistant_user_id", nullable = false)
    private Long assistantUserId;

    /** Nullable — clarification re-eval flows have no active task */
    @Column(name = "task_instance_id")
    private Long taskInstanceId;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}