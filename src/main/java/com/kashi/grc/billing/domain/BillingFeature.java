package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One row of the public comparison table — the BUYER's name for a capability.
 *
 * ── HOW A ROW BECOMES A TICK ─────────────────────────────────────────────────
 * The row's featureKey is matched against each plan's feature_keys_csv. That
 * CSV is the same list SubscriptionService.applyEntitlements grants from, so
 * the price list and what a tenant actually gets switched on cannot disagree:
 * there is one list, and this table only gives it a readable label.
 *
 *   includedInAll   core capability every customer plan has (evidence, issues,
 *                   your auditors). Ticked for every ORGANISATION plan without
 *                   being listed in any plan — these are not licensed modules.
 *   addonAvailable  when a plan does not include the key, the cell reads
 *                   "Add-on" instead of blank.
 *
 * featureKey is usually a LICENSED feature-flag key (module.risk_register). It
 * may also be a key with no flag yet (module.audit) — applyEntitlements skips
 * keys that are not LICENSED flags, so listing one is harmless.
 *
 * Platform-owned, not per tenant.
 */
@Entity
@Table(name = "billing_features",
        uniqueConstraints = @UniqueConstraint(name = "uk_billing_feature_key", columnNames = "feature_key"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class BillingFeature extends BaseEntity {

    @Column(name = "feature_key", nullable = false, length = 100)
    private String featureKey;

    /** As a buyer would say it: "Risk register", not module.risk_register. */
    @Column(name = "label", nullable = false, length = 150)
    private String label;

    /** One line shown as a tooltip / sub-text on the website. */
    @Column(name = "description", length = 500)
    private String description;

    /** Comparison-table section, e.g. "Third-party risk". */
    @Column(name = "group_name", nullable = false, length = 80)
    @Builder.Default
    private String groupName = "Platform";

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "included_in_all", nullable = false)
    @Builder.Default
    private boolean includedInAll = false;

    @Column(name = "addon_available", nullable = false)
    @Builder.Default
    private boolean addonAvailable = false;

    /** Hidden rows stay usable in the admin plan editor but are not published. */
    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private boolean isPublic = true;
}
