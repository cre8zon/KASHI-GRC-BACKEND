package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A training course — ISO 27001 A.6.3, SOC 2 CC1.4, PCI DSS 12.6.
 *
 * Extends GlobalOrTenantEntity because tenant_id IS NULL marks a PLATFORM
 * library course every tenant can assign, exactly as the risk library works.
 * AuditableEntity would have forced tenant_id NOT NULL and made the library
 * impossible.
 *
 * ── THE THREE COMPLETION BARS ARE PER-COURSE ──────────────────────────────
 * requiredWatchPercent, quizPassPercent and requiresAttestation are columns
 * rather than platform constants because a three-minute policy refresher and a
 * forty-minute secure-coding course do not deserve the same bar. A single
 * global threshold would be tuned for one and wrong for the other.
 */
@Entity
@Table(name = "training_courses", indexes = {
        @Index(name = "idx_tc_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_tc_status",   columnList = "status"),
        @Index(name = "idx_tc_category", columnList = "category"),
        @Index(name = "idx_tc_tags",     columnList = "control_tags"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingCourse extends GlobalOrTenantEntity {

    @Column(name = "course_ref", length = 30)
    private String courseRef;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "category", length = 50)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.DRAFT;

    /**
     * Bumped on every unpublish. Assignments are keyed on it, so republished
     * content produces NEW assignments rather than quietly changing what an
     * existing completion attests to — the old records stay, pointing at the
     * version they were actually earned against.
     */
    @Column(name = "content_version", nullable = false)
    @Builder.Default
    private Integer contentVersion = 1;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    /** Contiguous coverage required of each video item, as a percent. */
    @Column(name = "required_watch_percent", nullable = false)
    @Builder.Default
    private Integer requiredWatchPercent = 90;

    /** NULL means the course has no quiz. Zero would mean "a quiz you cannot fail". */
    @Column(name = "quiz_pass_percent")
    private Integer quizPassPercent;

    @Column(name = "requires_attestation", nullable = false)
    @Builder.Default
    private boolean requiresAttestation = true;

    /** 12 for annual. NULL or 0 assigns once and never recurs. */
    @Column(name = "recurrence_months")
    @Builder.Default
    private Integer recurrenceMonths = 12;

    @Column(name = "control_tags", length = 500)
    private String controlTags;

    @Column(name = "framework_refs", length = 500)
    private String frameworkRefs;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "unpublished_at")
    private LocalDateTime unpublishedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    /** A platform library course. Read-only to every TENANT; SYSTEM owns it. */
    @Transient
    public boolean isLibraryCourse() { return getTenantId() == null; }

    /**
     * Can this caller modify the course at all — ignoring status.
     *
     * editable was previously "is this a tenant row", copied from the risk
     * library where that IS the answer. It is not the answer here: a platform
     * training course is authored and maintained by the SYSTEM user, so their
     * own course was reporting read-only and the full-page detail hid every
     * button on it.
     */
    @Transient
    public boolean isOwnedBy(Long callerTenantId, boolean systemUser) {
        return isLibraryCourse() ? systemUser
                : getTenantId().equals(callerTenantId);
    }

    @Transient
    public boolean hasQuiz() { return quizPassPercent != null; }
}