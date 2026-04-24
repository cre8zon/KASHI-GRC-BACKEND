package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Feature flags control which features are enabled per tenant or role.
 * 'vendor_bulk_import', 'ai_risk_scoring', 'sso_login'.
 * Toggle a feature = update one row. No code deploy.
 */
@Entity
@Table(name = "feature_flags")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class FeatureFlag extends BaseEntity {

    @Column(name = "flag_key", nullable = false, length = 100)
    private String flagKey;

    @Column(name = "is_enabled")
    @Builder.Default
    private boolean isEnabled = false;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Role sides this flag applies to. JSON: '["ORGANIZATION"]'. NULL = all. */
    @Column(name = "allowed_sides_json", columnDefinition = "JSON")
    private String allowedSidesJson;

    /** NULL = global; set for per-tenant feature rollout */
    @Column(name = "tenant_id")
    private Long tenantId;
}
