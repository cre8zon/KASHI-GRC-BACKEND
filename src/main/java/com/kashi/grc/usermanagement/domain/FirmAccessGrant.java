package com.kashi.grc.usermanagement.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A client tenant permitting an audit firm to staff work inside it.
 *
 * This is the client's decision and only the client's: which firm may operate
 * here, and until when. It names no individuals — the client never sees or
 * manages the firm's roster, and crucially never creates an identity for
 * someone else's employee.
 *
 * Who actually staffs the engagement is the firm's decision, expressed as GUEST
 * rows in user_tenant_memberships pointing back at this grant. The client keeps
 * a veto at both levels: revoke one auditor, or revoke the grant and take the
 * whole firm out at once.
 */
@Entity
@Table(name = "firm_access_grants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_client_firm", columnNames = {"client_tenant_id", "firm_tenant_id"}),
        indexes = {
                @Index(name = "idx_fag_client", columnList = "client_tenant_id"),
                @Index(name = "idx_fag_firm",   columnList = "firm_tenant_id"),
                @Index(name = "idx_fag_status", columnList = "status")
        })
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class FirmAccessGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The tenant being audited. */
    @Column(name = "client_tenant_id", nullable = false)
    private Long clientTenantId;

    /** The audit firm's tenant. Must have tenants.is_audit_firm = true. */
    @Column(name = "firm_tenant_id", nullable = false)
    private Long firmTenantId;

    /** ACTIVE | REVOKED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * Ceiling for every membership written under this grant. An individual
     * auditor may be given a shorter window but never a longer one, so the
     * client can bound a whole engagement team from one place.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Usable now — the firm may assign people under it. */
    @Transient
    public boolean isUsable() {
        return "ACTIVE".equalsIgnoreCase(status)
                && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
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