package com.kashi.grc.usermanagement.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One row per (user, tenant) the identity is allowed to act in.
 *
 * WHY THIS EXISTS
 *   users.tenant_id is single-valued and email is globally unique
 *   (UserServiceImpl.createUser), so before this table one person could exist in
 *   exactly one tenant. An external auditor needs to hold an account in their
 *   own firm's tenant and in each client tenant they audit.
 *
 * WHAT IT DOES NOT CHANGE
 *   users.tenant_id stays as the HOME tenant and remains authoritative for
 *   everyone with a single membership — which is every user except invited
 *   auditors. The overlay in UtilityService only diverges from it when the JWT
 *   names a different tenant.
 *
 * ROLES
 *   Roles are global (roles.tenant_id IS NULL) and carry a side, so the same
 *   person can be ORGANIZATION-side in their firm's tenant and AUDITOR-side in a
 *   client's. That is why user_roles now points at a membership rather than at
 *   the user: the "all roles from exactly one side" rule holds per membership,
 *   not per identity.
 */
@Entity
@Table(name = "user_tenant_memberships",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_tenant", columnNames = {"user_id", "tenant_id"}),
        indexes = {
                @Index(name = "idx_utm_user",   columnList = "user_id"),
                @Index(name = "idx_utm_tenant", columnList = "tenant_id"),
                @Index(name = "idx_utm_firm",   columnList = "firm_tenant_id"),
                @Index(name = "idx_utm_status", columnList = "status")
        })
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class UserTenantMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** HOME = the tenant the identity belongs to. GUEST = invited external auditor. */
    @Column(name = "membership_type", nullable = false, length = 20)
    @Builder.Default
    private String membershipType = "HOME";

    /**
     * The audit firm this guest belongs to, as a tenant id. Lets a client revoke
     * a whole firm in one UPDATE instead of chasing individual accounts.
     * NULL for HOME memberships.
     */
    @Column(name = "firm_tenant_id")
    private Long firmTenantId;

    /** ACTIVE | SUSPENDED | REVOKED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /** Time-boxes an auditor to the audit period. NULL = no expiry. */
    @Column(name = "access_expires_at")
    private LocalDateTime accessExpiresAt;

    /** Which tenant this user lands in at login when they hold several. */
    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private boolean isPrimary = false;

    @Column(name = "invited_by")
    private Long invitedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Usable right now — active, and either no expiry or not yet past it.
     * Checked at login and on every tenant switch; an expired membership must
     * not be selectable, or an auditor keeps access after the audit period ends.
     */
    @Transient
    public boolean isUsable() {
        return "ACTIVE".equalsIgnoreCase(status)
                && (accessExpiresAt == null || accessExpiresAt.isAfter(LocalDateTime.now()));
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}