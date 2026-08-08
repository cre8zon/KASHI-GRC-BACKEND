package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

/**
 * RBAC role. Maps to the `roles` table.
 * System roles have tenant_id = null; tenant-specific roles have tenant_id set.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Role extends BaseEntity {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 50)
    private RoleSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", length = 10)
    private RoleLevel level;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_system")
    @Builder.Default
    private Boolean isSystem = false;

    /**
     * ACTIVE | SUSPENDED. A suspended role is hidden from the assignable
     * role catalogue (role pickers, invite/onboard forms) but stays fully
     * visible in RBAC admin so it can be edited and reactivated — the point
     * is to park a role that isn't fully built out yet without deleting it.
     *
     * Suspending does NOT revoke the role from users who already hold it.
     * See RoleServiceImpl.setRoleStatus for the reasoning.
     *
     * Defaults to ACTIVE so every existing row keeps working unchanged when
     * the column is added (ddl-auto=update leaves existing rows NULL, which
     * the queries treat as ACTIVE — see RoleRepositoryImpl).
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    @Builder.Default
    private Set<Permission> permissions = new HashSet<>();
}