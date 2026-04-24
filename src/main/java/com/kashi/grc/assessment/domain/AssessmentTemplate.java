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
public class AssessmentTemplate extends GlobalOrTenantEntity {

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
}