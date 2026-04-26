package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Core user entity. Maps to the `users` table.
 * Multi-tenant: every user belongs to exactly one tenant (via TenantAwareEntity.tenantId).
 * Vendor users have vendor_id set; org users have vendor_id = null.
 *
 * ── Entity Graph strategy ────────────────────────────────────────────────────
 * roles and permissions are LAZY by default — never auto-joined on simple lookups.
 * Use named graphs on repository queries that actually need them:
 *
 *   User.WITH_ROLES              — roles only (workflow access checks, sidebar)
 *   User.WITH_ROLES_PERMISSIONS  — roles + permissions (auth/login, JWT validation)
 *
 * This replaces the old EAGER fetch which fired a full JOIN on every user load
 * regardless of whether roles were needed — the primary cause of slow list/login.
 */
@NamedEntityGraphs({
        @NamedEntityGraph(
                name = User.WITH_ROLES,
                attributeNodes = @NamedAttributeNode("roles")
        ),
        @NamedEntityGraph(
                name = User.WITH_ROLES_PERMISSIONS,
                attributeNodes = @NamedAttributeNode(value = "roles", subgraph = "roles-with-permissions"),
                subgraphs = @NamedSubgraph(
                        name = "roles-with-permissions",
                        attributeNodes = @NamedAttributeNode("permissions")
                )
        )
})
@Entity
@Table(name = "users")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class User extends AuditableEntity {

    // Graph name constants — use these instead of magic strings in @EntityGraph annotations
    public static final String WITH_ROLES             = "User.withRoles";
    public static final String WITH_ROLES_PERMISSIONS = "User.withRolesAndPermissions";

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "department", length = 255)
    private String department;

    @Column(name = "job_title", length = 255)
    private String jobTitle;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "phone", length = 50)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "password_reset_token", length = 255)
    private String passwordResetToken;

    @Column(name = "password_reset_expiry")
    private LocalDateTime passwordResetExpiry;

    /** Set to true on first provisioning; forces password change on next login */
    @Column(name = "password_reset_required")
    @Builder.Default
    private Boolean passwordResetRequired = true;

    @Column(name = "failed_login_attempts")
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column(name = "lockout_until")
    private LocalDateTime lockoutUntil;

    @Column(name = "timezone", length = 100)
    @Builder.Default
    private String timezone = "UTC";

    // ── Relationships ─────────────────────────────────────────────
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<UserAttribute> attributes = new HashSet<>();

    // ── Helpers ───────────────────────────────────────────────────
    public String getFullName() {
        return (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "");
    }

    public boolean isActive() {
        return UserStatus.ACTIVE.equals(status);
    }

    public boolean isLocked() {
        return UserStatus.LOCKED.equals(status);
    }

    public void incrementFailedAttempts() {
        this.failedLoginAttempts = (this.failedLoginAttempts == null ? 0 : this.failedLoginAttempts) + 1;
    }

    public void resetFailedAttempts() {
        this.failedLoginAttempts = 0;
        this.lockoutUntil = null;
    }
}