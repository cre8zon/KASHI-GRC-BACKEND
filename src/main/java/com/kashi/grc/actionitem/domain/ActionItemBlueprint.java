package com.kashi.grc.actionitem.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Template library for standard action items.
 *
 * Extends GlobalOrTenantEntity:
 *   tenantId = null  → global system blueprint (ISO 27001, SOC2, GDPR)
 *                      visible to all tenants, read-only for tenants
 *   tenantId = X     → tenant-custom blueprint, visible only to that tenant
 *
 * Ad-hoc action items (REVISION_REQUEST) have no blueprint — blueprint_id is
 * nullable on ActionItem. Blueprints are only useful for repeatable,
 * pre-defined findings (audit controls, compliance checks, standard remediations).
 */
@Entity
@Table(name = "action_item_blueprints", indexes = {
    @Index(name = "idx_aib_tenant",      columnList = "tenant_id"),
    @Index(name = "idx_aib_source_type", columnList = "source_type"),
    @Index(name = "idx_aib_category",    columnList = "category"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ActionItemBlueprint extends GlobalOrTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private ActionItem.SourceType sourceType;

    /** Logical grouping — e.g. DATA_GOVERNANCE, ACCESS_CONTROL, ENCRYPTION */
    @Column(name = "category", length = 60)
    private String category;

    /** Display name for this blueprint */
    @Column(name = "title_template", nullable = false)
    private String titleTemplate;

    /** Longer description / guidance text. Supports {variable} placeholders. */
    @Column(name = "description_template", columnDefinition = "TEXT")
    private String descriptionTemplate;

    /**
     * Role name that can mark instances of this blueprint as RESOLVED.
     * e.g. 'LEAD_AUDITOR', 'ORG_REVIEWER', 'VENDOR_RESPONDER'
     * Copied to ActionItem.resolutionRole at instantiation time.
     */
    @Column(name = "resolution_role", length = 60)
    private String resolutionRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_priority", nullable = false, length = 10)
    @Builder.Default
    private ActionItem.Priority defaultPriority = ActionItem.Priority.MEDIUM;

    /** Standard reference e.g. 'ISO27001-A.12.1.1', 'SOC2-CC6.1', 'GDPR-Art32' */
    @Column(name = "standard_ref", length = 100)
    private String standardRef;

    /** Short code for programmatic lookup e.g. 'ISO27001_CHANGE_MGMT' */
    @Column(name = "blueprint_code", unique = true, length = 80)
    private String blueprintCode;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
