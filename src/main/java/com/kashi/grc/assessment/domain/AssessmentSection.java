package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A reusable section in the library.
 *
 * DESIGN: This entity has NO foreign key to AssessmentTemplate and no order_no.
 * The section↔template relationship and its order are managed via
 * TemplateSectionMapping (join table).
 *
 * One section can be reused in multiple templates — zero duplication.
 *
 * tenant_id = null  → global section created by Platform Admin
 * tenant_id = X     → private section created by org X
 */
@Entity
@Table(name = "assessment_sections")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentSection extends GlobalOrTenantEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;
}