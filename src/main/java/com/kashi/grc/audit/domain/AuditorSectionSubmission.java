package com.kashi.grc.audit.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Tracks when an auditor submits their test results for a specific section.
 * Mirrors ContributorSectionSubmission exactly.
 *
 * One row per (auditor_user_id, section_instance_id).
 * Used to:
 *   - Lock auditor's test results for that section (read-only after submit)
 *   - Determine when ALL auditor sections are submitted → fire completion event
 *   - Audit trail: who submitted what and when
 */
@Entity
@Table(name = "auditor_section_submissions",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"section_instance_id", "auditor_user_id"}
    )
)
@Getter @Setter
@lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class AuditorSectionSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    @Column(name = "section_instance_id", nullable = false)
    private Long sectionInstanceId;

    @Column(name = "auditor_user_id", nullable = false)
    private Long auditorUserId;

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
