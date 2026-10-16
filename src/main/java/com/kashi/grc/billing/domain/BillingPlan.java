package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A packaging tier in the plan catalogue — WHAT a customer gets, never what it costs.
 *
 * ── WHY PRICE IS NOT ON THIS ROW ──────────────────────────────────────────
 * Packaging (modules, limits, AI allowance) changes rarely and is a product
 * decision. Price changes per currency, per billing interval and per pricing
 * experiment, and is a commercial decision. Keeping them in one row means a
 * price change edits the same record that decides entitlements. Prices live in
 * {@link BillingPlanPrice}, one row per (plan, currency, interval).
 *
 * ── WHAT IT IS PRICED ON ──────────────────────────────────────────────────
 * pricingMetric = VENDORS means the tier's headline number is vendors under
 * management, with seats unlimited (includedUsers = null). That is the
 * recommendation; SEATS and FLAT exist so the catalogue does not have to be
 * migrated if a tier is ever packaged differently.
 *
 * ── ENTITLEMENTS ──────────────────────────────────────────────────────────
 * featureKeysCsv lists LICENSED feature-flag keys this tier includes. Activating
 * a subscription can apply them through FeatureEntitlementService, which stays
 * the single enforcement point. The catalogue only proposes; the flag decides.
 */
@Entity
@Table(name = "billing_plans",
        uniqueConstraints = @UniqueConstraint(name = "uk_billing_plan_code", columnNames = "code"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class BillingPlan extends BaseEntity {

    /** Stable key written into tenant_subscriptions.plan_code and tenants.plan. e.g. STARTER */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "tagline", length = 255)
    private String tagline;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Ordering on the ladder: 1 = entry tier. Used to tell an upgrade from a downgrade. */
    @Column(name = "tier_rank", nullable = false)
    @Builder.Default
    private Integer tierRank = 1;

    /** VENDORS | SEATS | ENTITIES | FLAT */
    @Column(name = "pricing_metric", nullable = false, length = 20)
    @Builder.Default
    private String pricingMetric = "VENDORS";

    /** Vendors under management included. NULL = unlimited. */
    @Column(name = "included_vendors")
    private Integer includedVendors;

    /** Seats included. NULL = unlimited (the recommended default). */
    @Column(name = "included_users")
    private Integer includedUsers;

    /** Entities / legal entities in scope (Enterprise). NULL = not metered. */
    @Column(name = "included_entities")
    private Integer includedEntities;

    /** AI token allowance per calendar month. NULL = no AI. 0 = AI off. */
    @Column(name = "ai_monthly_tokens")
    private Long aiMonthlyTokens;

    /** Comma-separated LICENSED feature-flag keys included in this tier. */
    @Column(name = "feature_keys_csv", columnDefinition = "TEXT")
    private String featureKeysCsv;

    /** Human-readable bullet list for the plan card, one per line. */
    @Column(name = "highlights", columnDefinition = "TEXT")
    private String highlights;

    /** EMAIL | ONBOARDING | NAMED_CONTACT_SLA */
    @Column(name = "support_level", length = 30)
    private String supportLevel;

    /** Shown on the public price list. Enterprise is usually false (negotiated). */
    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private boolean isPublic = true;

    /** Retired plans stay for history (subscriptions point at them) but cannot be sold. */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "trial_days")
    @Builder.Default
    private Integer trialDays = 30;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    // ── Public price list (www.kashiguard.com/pricing) ──────────────────────
    // How the plan is PRESENTED. None of these change entitlements or what a
    // subscription costs — a subscription records its own contract value.

    /** ORGANISATION (a customer plan) | AUDIT_FIRM (the free partner tier). */
    @Column(name = "audience", length = 20)
    @Builder.Default
    private String audience = "ORGANISATION";

    /**
     * FIXED (show the list price) | STARTING_AT ("from ₹…") |
     * CONTACT_SALES (no number) | FREE.
     * A FREE plan is also provisioned as a zero-value ACTIVE subscription.
     */
    @Column(name = "price_display", length = 20)
    @Builder.Default
    private String priceDisplay = "CONTACT_SALES";

    /** What the plan's button asks for: TRIAL | DEMO | CONTACT_SALES | APPLY. Becomes the enquiry type. */
    @Column(name = "cta_type", length = 20)
    @Builder.Default
    private String ctaType = "DEMO";

    /** Button text; blank = a default per ctaType. */
    @Column(name = "cta_label", length = 60)
    private String ctaLabel;

    /** Ribbon on the card, e.g. "Most teams start here". Blank = none. */
    @Column(name = "badge", length = 40)
    private String badge;

    /** Frameworks / audit programmes included. NULL = unlimited. */
    @Column(name = "included_frameworks")
    private Integer includedFrameworks;

    /** Small print under the price, e.g. "billed annually, excl. GST". */
    @Column(name = "public_note", length = 255)
    private String publicNote;
}
