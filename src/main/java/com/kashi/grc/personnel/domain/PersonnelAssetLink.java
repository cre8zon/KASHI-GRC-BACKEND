package com.kashi.grc.personnel.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * An asset issued to a person, and whether it came back.
 *
 * ── WHY NOT assets.custodian_id ───────────────────────────────────────────
 * That column points at a USER, so it cannot represent a contractor with no
 * login — exactly the people most likely to leave with a laptop still in the
 * boot of their car. A.5.11 asks about all of them.
 *
 * returnedAt NULL on an OFFBOARDED person is the finding.
 * PersonnelService.completeOffboarding refuses while any exist.
 */
@Entity
@Table(
        name = "personnel_asset_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_personnel_asset",
                                  columnNames = {"personnel_id", "asset_id"})
        },
        indexes = {
                @Index(name = "idx_pal_personnel", columnList = "personnel_id"),
                @Index(name = "idx_pal_asset",     columnList = "asset_id"),
                @Index(name = "idx_pal_tenant",    columnList = "tenant_id"),
                @Index(name = "idx_pal_returned",  columnList = "tenant_id,returned_at"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PersonnelAssetLink extends TenantAwareEntity {

    @Column(name = "personnel_id", nullable = false)
    private Long personnelId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "assignment_note", length = 500)
    private String assignmentNote;

    @Column(name = "created_by")
    private Long createdBy;

    @Transient
    public boolean isOutstanding() { return returnedAt == null; }
}
