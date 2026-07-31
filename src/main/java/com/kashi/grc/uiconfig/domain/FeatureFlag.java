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

    /**
     * Entitlement MODE — lives on the GLOBAL row (tenant_id = null), which is the
     * feature's catalogue definition. A feature is in exactly ONE mode at a time:
     *   GLOBAL   → the global row's isEnabled decides for everyone; no active
     *              tenant rows exist (any are soft-deleted).
     *   LICENSED → per-tenant rows decide; the global row grants nothing.
     * This makes "global vs licensed" explicit rather than inferred, and enforces
     * the invariant that a feature is never both at once.
     */
    @Column(name = "mode", length = 20)
    @Builder.Default
    private String mode = "GLOBAL";

    /** Soft-delete marker (industry 'nullable timestamp' pattern). A row with
     *  deletedAt != null is inactive — the resolver ignores it — but is retained
     *  for licensing history (who had what, when). Restore by clearing it. */
    @Column(name = "deleted_at")
    private java.time.LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;
}