package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Snapshot of an AssessmentTemplate at the moment a VendorAssessment is triggered.
 * Locks the template version — future edits to the template don't affect this instance.
 *
 * VendorAssessment (1) ──→ AssessmentTemplateInstance (1)
 * AssessmentTemplateInstance (1) ──→ AssessmentSectionInstance (many)
 */
@Entity
@Table(name = "assessment_template_instances")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AssessmentTemplateInstance extends TenantAwareEntity {

    @Column(name = "assessment_id", nullable = false, unique = true)
    private Long assessmentId;               // FK → vendor_assessments.id

    @Column(name = "original_template_id", nullable = false)
    private Long originalTemplateId;         // FK → assessment_templates.id

    @Column(name = "template_name_snapshot", nullable = false)
    private String templateNameSnapshot;     // locked name at trigger time

    @Column(name = "template_version_snapshot")
    private Integer templateVersionSnapshot; // locked version at trigger time

    @Column(name = "snapshotted_at", nullable = false)
    private LocalDateTime snapshottedAt;
}