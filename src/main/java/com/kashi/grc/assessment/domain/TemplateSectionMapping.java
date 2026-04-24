package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Join table linking AssessmentTemplate ↔ AssessmentSection.
 *
 * WHY THIS EXISTS:
 *   Previously sections had template_id and order_no as columns.
 *   That meant one section could only belong to one template.
 *   This join table allows one section to appear in multiple templates,
 *   each with its own display order.
 *
 * orderNo — the position of this section within this specific template.
 */
@Entity
@Table(
        name = "template_section_mappings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_template_section",
                columnNames = {"template_id", "section_id"}
        )
)
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateSectionMapping extends BaseEntity {

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;
}