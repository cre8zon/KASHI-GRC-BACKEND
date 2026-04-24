package com.kashi.grc.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Extends BaseEntity with tenant isolation.
 * Every entity that belongs to an org must extend this class.
 * The TenantIsolationAspect enforces that queries always filter by tenantId.
 */
@Getter
@Setter
@MappedSuperclass
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public abstract class TenantAwareEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
}
