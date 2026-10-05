package com.kashi.grc.incident.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Which systems were involved. The first question in any post-incident review
 * and the first thing a regulator asks.
 */
@Entity
@Table(
        name = "incident_asset_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_incident_asset",
                                  columnNames = {"incident_id", "asset_id"})
        },
        indexes = {
                @Index(name = "idx_ial_incident", columnList = "incident_id"),
                @Index(name = "idx_ial_asset",    columnList = "asset_id"),
                @Index(name = "idx_ial_tenant",   columnList = "tenant_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentAssetLink extends TenantAwareEntity {

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "impact_note", length = 500)
    private String impactNote;

    @Column(name = "created_by")
    private Long createdBy;
}
