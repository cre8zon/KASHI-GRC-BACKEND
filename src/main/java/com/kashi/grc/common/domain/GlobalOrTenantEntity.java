package com.kashi.grc.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@MappedSuperclass
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public abstract class GlobalOrTenantEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = true)  // nullable = global
    private Long tenantId;
}
