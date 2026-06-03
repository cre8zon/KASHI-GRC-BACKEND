package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AuditProject — top-level container for audit engagements.
 *
 * Extends GlobalOrTenantEntity so both Platform Admin and org users can create projects:
 *   tenantId = null  → global project (Platform Admin) — visible to all orgs
 *   tenantId = X     → tenant-private project (Org) — visible only to that org
 *
 * This mirrors the same pattern as AuditTemplate, AuditSection, AuditControl.
 *
 * One project can have multiple engagements (e.g. multiple frameworks audited
 * as part of the same programme).
 *
 * projectRef: human-readable reference, e.g. PROJ-2026-0001
 * ownerId: CAE (Chief Audit Executive) or Audit Manager who owns this programme
 */
@Entity
@Table(name = "audit_projects",
        indexes = {
                @Index(name = "idx_audit_proj_tenant", columnList = "tenant_id"),
                @Index(name = "idx_audit_proj_status", columnList = "status"),
                @Index(name = "idx_audit_proj_owner",  columnList = "owner_id")
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

    @Column(name = "owner_id")
    private Long ownerId;   // CAE / Audit Manager

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "planned_start")
    private LocalDate plannedStart;

    @Column(name = "planned_end")
    private LocalDate plannedEnd;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    /** Workflow for the project lifecycle itself (optional) */
    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    public enum Status {
        PLANNING,
        IN_PROGRESS,
        ON_HOLD,
        COMPLETED,
        CANCELLED
    }
}