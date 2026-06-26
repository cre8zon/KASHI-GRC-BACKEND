package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AuditProject — library-level programme template.
 *
 * PUBLISH STATUS (publishStatus):
 *   DRAFT     → platform admin working on it, never shown to org LOOKUP
 *   PUBLISHED → visible to orgs (subject to visibility rules)
 *
 * VISIBILITY (visibility):
 *   GLOBAL    → shown to ALL orgs
 *   PLATFORM  → platform admin internal only, not shown to any org
 *   SPECIFIC  → shown only to tenants in audit_project_tenant_access table
 */
@Entity
@Table(name = "audit_projects",
        indexes = {
                @Index(name = "idx_audit_proj_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_audit_proj_status",  columnList = "status"),
                @Index(name = "idx_audit_proj_publish", columnList = "publish_status"),
                @Index(name = "idx_audit_proj_owner",   columnList = "owner_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditProject extends GlobalOrTenantEntity {

    @Column(name = "project_ref", length = 30)
    private String projectRef;

    @Column(name = "project_name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PLANNING;

    /** Library publish status — controls org visibility */
    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", nullable = false, length = 20)
    @Builder.Default
    private PublishStatus publishStatus = PublishStatus.DRAFT;

    /** Visibility scope */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private Visibility visibility = Visibility.GLOBAL;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "planned_start")
    private LocalDate plannedStart;

    @Column(name = "planned_end")
    private LocalDate plannedEnd;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    public enum Status {
        PLANNING, IN_PROGRESS, ON_HOLD, COMPLETED, CANCELLED
    }

    public enum PublishStatus {
        DRAFT, PUBLISHED
    }

    public enum Visibility {
        GLOBAL,    // all orgs
        PLATFORM,  // platform admin only
        SPECIFIC   // only tenants in audit_project_tenant_access
    }
}