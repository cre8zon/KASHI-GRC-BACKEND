package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Snapshot of an AssessmentSection at the moment a VendorAssessment is triggered.
 *
 * assignedUserId  — set during Step 3 (CISO assigns sections to Responders).
 * submittedAt     — set when the Responder submits this section (step 4).
 *                   Null = editable. Non-null = locked (read-only for responder).
 * reopenedAt      — set when CISO/VRM/ORG_ADMIN reopens a submitted section.
 *                   Clears submittedAt — section becomes editable again.
 *                   Does NOT restore contributor assignments (responder must re-assign).
 */
@Entity
@Table(name = "assessment_section_instances")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentSectionInstance extends BaseEntity {

    @Column(name = "template_instance_id", nullable = false)
    private Long templateInstanceId;

    /** Reference to original — for audit only, never used for display */
    @Column(name = "original_section_id")
    private Long originalSectionId;

    @Column(name = "section_name_snapshot", nullable = false, length = 255)
    private String sectionNameSnapshot;

    @Column(name = "section_order_no", nullable = false)
    @Builder.Default
    private Integer sectionOrderNo = 0;

    /**
     * Set during Step 3: CISO assigns this section to a Responder.
     * Null until assignment is made.
     */
    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    /**
     * Set when the assigned Responder submits this section.
     * Non-null = section is locked (read-only). Null = editable.
     * Only CISO/VRM/ORG_ADMIN can clear this via reopen endpoint.
     */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    /**
     * Set when CISO/VRM/ORG_ADMIN reopens a submitted section.
     * Contributor question assignments are NOT restored — responder must re-assign.
     */
    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopened_by")
    private Long reopenedBy;

    @Column(name = "reviewer_assigned_user_id")
    private Long reviewerAssignedUserId;

    // Reviewer-side section lock — mirrors vendor submittedAt but for org review
    @Column(name = "reviewer_submitted_at")
    private LocalDateTime reviewerSubmittedAt;

    @Column(name = "reviewer_submitted_by")
    private Long reviewerSubmittedBy;

    @Column(name = "reviewer_reopened_at")
    private LocalDateTime reviewerReopenedAt;

    @Column(name = "reviewer_reopened_by")
    private Long reviewerReopenedBy;
}