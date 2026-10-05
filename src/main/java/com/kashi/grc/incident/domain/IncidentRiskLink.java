package com.kashi.grc.incident.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A risk that materialised.
 *
 * This is the link that closes the loop the register exists for: an incident
 * traced to a risk is evidence that the risk's treatment was inadequate, which
 * is exactly what Clause 8.2 asks an organisation to demonstrate it notices.
 */
@Entity
@Table(
        name = "incident_risk_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_incident_risk",
                                  columnNames = {"incident_id", "risk_id"})
        },
        indexes = {
                @Index(name = "idx_irl_incident", columnList = "incident_id"),
                @Index(name = "idx_irl_risk",     columnList = "risk_id"),
                @Index(name = "idx_irl_tenant",   columnList = "tenant_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentRiskLink extends TenantAwareEntity {

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "risk_id", nullable = false)
    private Long riskId;

    @Column(name = "link_note", length = 500)
    private String linkNote;

    @Column(name = "created_by")
    private Long createdBy;
}
