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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    @Builder.Default
    private Set<Permission> permissions = new HashSet<>();
}