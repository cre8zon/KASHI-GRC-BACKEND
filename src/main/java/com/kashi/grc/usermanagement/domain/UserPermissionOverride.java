package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * User-level permission override — wins over role-level PermissionGrant.
 *
 * NEW: permissionCode (denormalized) — stored at grant time for WorkflowAccessService hot path.
 *
 * MIGRATION:
 *   ALTER TABLE user_permission_overrides
 *     ADD COLUMN permission_code VARCHAR(100) NULL;
 */
@Entity
@Table(name = "user_permission_overrides",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_permission",
                columnNames = {"user_id", "permission_id"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UserPermissionOverride extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    /** Denormalized — stored at grant time, avoids join in WorkflowAccessService hot path */
    @Column(name = "permission_code", length = 100)
    private String permissionCode;

    @Column(name = "granted", nullable = false)
    @Builder.Default
    private boolean granted = true;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}