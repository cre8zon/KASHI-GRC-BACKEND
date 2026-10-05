package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One tenant's decision that a course is required.
 *
 * ── THE PLATFORM OFFERS, THE TENANT DECIDES ───────────────────────────────
 * A global course being available is not the same as it being mandatory. Which
 * training is mandatory follows from an organisation's own risk assessment,
 * frameworks and jurisdiction — so the requirement lives here, keyed to the
 * tenant, and never on the course itself.
 *
 * Auto-assigning every library course to every tenant would give each of them
 * assignments for content they never chose, and overdue rows they could only
 * clear by cancelling. A register nobody can clear is a register nobody reads.
 */
@Entity
@Table(
        name = "tenant_course_requirements",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_tenant_course",
                        columnNames = {"tenant_id", "course_id"})
        },
        indexes = {
                @Index(name = "idx_tcr_tenant", columnList = "tenant_id,is_active"),
                @Index(name = "idx_tcr_course", columnList = "course_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCourseRequirement extends TenantAwareEntity {

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    /** Assign automatically when a person becomes ACTIVE. */
    @Column(name = "required_on_joining", nullable = false)
    @Builder.Default
    private boolean requiredOnJoining = true;

    /** Days from becoming ACTIVE. Per tenant, because a week and a month are both defensible. */
    @Column(name = "due_days", nullable = false)
    @Builder.Default
    private Integer dueDays = 30;

    /** Overrides the course's recurrence. NULL follows the course. */
    @Column(name = "recurrence_months")
    private Integer recurrenceMonths;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;
}