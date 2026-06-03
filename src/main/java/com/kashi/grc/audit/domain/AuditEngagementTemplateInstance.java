package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Snapshot of AuditTemplate at the moment an AuditEngagement is created.
 * Mirrors AssessmentTemplateInstance exactly.
 *
 * Locks the template version — future edits to the library do not affect
 * running engagements.
 *
 * AuditEngagement (1) ──→ AuditEngagementTemplateInstance (1)
 * AuditEngagementTemplateInstance (1) ──→ AuditSectionInstance (many)
 * AuditSectionInstance (1) ──→ AuditControlInstance (many)
 */
@Entity
@Table(name = "audit_engagement_template_instances")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditEngagementTemplateInstance extends TenantAwareEntity {

    @Column(name = "engagement_id", nullable = false, unique = true)
    private Long engagementId;

    @Column(name = "original_template_id", nullable = false)
    private Long originalTemplateId;

    @Column(name = "template_name_snapshot", nullable = false, length = 255)
    private String templateNameSnapshot;

    @Column(name = "template_version_snapshot")
    private Integer templateVersionSnapshot;

    @Column(name = "framework_ref_snapshot", length = 100)
    private String frameworkRefSnapshot;

    @Column(name = "snapshotted_at", nullable = false)
    private LocalDateTime snapshottedAt;
}
