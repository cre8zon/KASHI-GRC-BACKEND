package com.kashi.grc.assessment.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Tracks when a contributor submits their answers for a specific section.
 * One row per (contributor_user_id, section_instance_id).
 *
 * Used to:
 *  - Lock contributor's answers for that section (read-only after submit)
 *  - Determine when ALL contributor's sections are submitted → auto-approve sub-task
 *  - Audit trail: who submitted what and when
 */
@Entity
@Table(name = "contributor_section_submissions",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"section_instance_id", "contributor_user_id"}))
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class ContributorSectionSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "section_instance_id", nullable = false)
    private Long sectionInstanceId;

    @Column(name = "contributor_user_id", nullable = false)
    private Long contributorUserId;

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}