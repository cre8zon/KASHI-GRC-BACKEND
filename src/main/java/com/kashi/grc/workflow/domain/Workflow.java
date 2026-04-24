package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Workflow blueprint — Platform Admin creates this globally (tenant_id = null).
 * All orgs share the same blueprint. No org can create or modify workflows.
 *
 * Blueprint is NEVER modified when an instance runs. If the process needs to change,
 * Platform Admin creates a new version via createNewVersion(). In-flight instances
 * continue using the original step IDs.
 *
 * Uses GlobalOrTenantEntity (same as AssessmentTemplate) — tenant_id nullable.
 * tenant_id = null → global, visible to all orgs.
 */
@Entity
@Table(name = "workflows",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_tenant_name_version",
           columnNames = {"tenant_id", "name", "version"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class Workflow extends GlobalOrTenantEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** e.g. VENDOR, AUDIT, CONTRACT — used to look up applicable workflows */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = false;
}
