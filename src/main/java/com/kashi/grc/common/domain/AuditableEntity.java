package com.kashi.grc.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/**
 * Extends TenantAwareEntity with user-level audit tracking.
 * Captures who created and last modified an entity.
 */
@Getter
@Setter
@MappedSuperclass
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public abstract class AuditableEntity extends TenantAwareEntity {

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default // Add this to tell the Builder to use the default value
    private boolean isDeleted = false;
}
