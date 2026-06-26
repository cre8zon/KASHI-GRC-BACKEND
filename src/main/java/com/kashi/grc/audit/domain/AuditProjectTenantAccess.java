package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditProjectTenantAccess — controls which tenants can see a SPECIFIC-visibility
 * AuditProject in their org-side LOOKUP.
 *
 * Only relevant when AuditProject.visibility = SPECIFIC.
 * GLOBAL/PLATFORM projects ignore this table entirely.
 */
@Entity
@Table(name = "audit_project_tenant_access",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_proj_tenant", columnNames = {"project_id", "tenant_id"}),
        indexes = {
                @Index(name = "idx_proj_tenant_proj",   columnList = "project_id"),
                @Index(name = "idx_proj_tenant_tenant", columnList = "tenant_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AuditProjectTenantAccess extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "granted_by")
    private Long grantedBy;
}