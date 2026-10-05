package com.kashi.grc.onboarding.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A reusable onboarding checklist.
 *
 * ── WHY THIS IS CONFIGURABLE AND OFFBOARDING IS NOT ───────────────────────
 * Offboarding is five fixed columns on personnel — accessRevokedAt,
 * credentialsRotatedAt and so on — because every organisation offboards the
 * same way and an auditor asks for the same evidence every time.
 *
 * Onboarding is not like that. One tenant issues a YubiKey, another runs a
 * two-week buddy programme, a third needs a police check before anyone touches
 * production. Fixed columns would fit nobody.
 */
@Entity
@Table(name = "onboarding_templates",
        indexes = @Index(name = "idx_onb_tpl_tenant", columnList = "tenant_id,is_active"))
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class OnboardingTemplate extends TenantAwareEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * Matches personnel.employment_type. NULL means everyone.
     *
     * A contractor should not inherit the full employee induction — that is how
     * a checklist becomes something people tick without reading.
     */
    @Column(name = "applies_to_employment_type", length = 30)
    private String appliesToEmploymentType;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;
}