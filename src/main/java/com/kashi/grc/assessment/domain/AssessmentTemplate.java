package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Assessment template — the top-level container.
 *
 * Status lifecycle:
 *   DRAFT → PUBLISHED → DRAFT (unpublish, Platform Admin only)
 *
 * Platform Admin (tenant_id = null) creates global templates visible to all.
 * Orgs can create private templates (tenant_id = their id).
 *
 * Sections are linked via TemplateSectionMapping — no template_id on sections.
 */
@Entity
@Table(name = "assessment_templates")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@org.hibernate.annotations.SQLRestriction("is_deleted = false")
public class AssessmentTemplate extends GlobalOrTenantEntity implements com.kashi.grc.common.domain.SoftDeletable {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * DRAFT     → being built, not yet available for assessments
     * PUBLISHED → live, available for vendor assessments
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** Set when Platform Admin unpublishes — for audit trail */
    @Column(name = "unpublished_at")
    private LocalDateTime unpublishedAt;
    // -- SOFT DELETE ----------------------------------------------------------
    //
    // Same four columns and the same @SQLRestriction the audit library carries,
    // for the same reason: deleting a library row used to remove it AND its
    // mappings, so every template built on it lost the row with no trace of
    // what had been there.
    //
    // @SQLRestriction covers HQL, criteria queries and collection loads - which
    // is every module list, since they all run through DbRepository's criteria
    // builder. It is NOT applied to a load by primary key, so findById still
    // returns a retired row; that is what makes restore work.
    //
    // ddl-auto=update adds the columns on the next boot. sql/114 adds the index.

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private java.time.LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "deletion_reason", length = 500)
    private String deletionReason;
}