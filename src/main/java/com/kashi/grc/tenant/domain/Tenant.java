package com.kashi.grc.tenant.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tenants")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class Tenant extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", unique = true, nullable = false)
    private String code;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status")
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "plan")
    private String plan;

    @Column(name = "max_users")
    private Integer maxUsers;

    @Column(name = "max_vendors")
    private Integer maxVendors;

    /**
     * Marks this tenant as an audit firm.
     *
     * A firm is an ordinary tenant in every other respect — its own users, its
     * own compliance posture, its own vendors. The flag exists so a client
     * inviting an external auditor can be offered real firms to pick from, and
     * so the platform-admin list distinguishes them from customers.
     *
     * It deliberately does NOT change how the firm's own tenant behaves. Any
     * future firm-only UI should hang off a feature flag, which is already
     * tenant-scoped, rather than off this column.
     */
    @Column(name = "is_audit_firm", nullable = false)
    @Builder.Default
    private boolean isAuditFirm = false;
}