package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Individual permission. Maps to the `permissions` table.
 * Example codes: VENDOR_VIEW, VENDOR_CREATE, ASSESSMENT_REVIEW.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Permission extends BaseEntity {

    @Column(name = "module_id", nullable = false)
    private Long moduleId;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "resource_type", length = 100)
    private String resourceType;
}
