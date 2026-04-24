package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "delegations")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Delegation extends TenantAwareEntity {

    @Column(name = "delegator_user_id", insertable = false, updatable = false)
    private Long delegatorUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegator_user_id", nullable = false)
    private User delegator;

    @Column(name = "delegatee_user_id", insertable = false, updatable = false)
    private Long delegateeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegatee_user_id", nullable = false)
    private User delegatee;

    @Column(name = "scope_type")
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    // Stored as comma-separated permission codes
    @Column(name = "permissions_delegated", columnDefinition = "TEXT")
    private String permissionsDelegated;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "status")
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoke_reason", columnDefinition = "TEXT")
    private String revokeReason;
}
