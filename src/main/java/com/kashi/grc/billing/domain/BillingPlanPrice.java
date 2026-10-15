package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One list price for a plan in one currency and one billing interval.
 *
 * Money is always minor units (paise, cents) as BIGINT with the ISO currency
 * beside it — never a DOUBLE, never an amount whose currency is implied.
 *
 * unitAmountMinor is the base price for the tier. When the plan is metered
 * (pricingMetric = VENDORS) and a customer buys more than includedVendors,
 * overageUnitMinor is the price per additional unit per interval.
 */
@Entity
@Table(name = "billing_plan_prices",
        uniqueConstraints = @UniqueConstraint(name = "uk_plan_price",
                columnNames = {"plan_id", "currency", "billing_interval"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class BillingPlanPrice extends BaseEntity {

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** ISO 4217: INR | USD | EUR … */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** MONTHLY | ANNUAL */
    @Column(name = "billing_interval", nullable = false, length = 10)
    private String billingInterval;

    @Column(name = "unit_amount_minor", nullable = false)
    private Long unitAmountMinor;

    /** Price per unit above the included quantity, per interval. NULL = no overage sold. */
    @Column(name = "overage_unit_minor")
    private Long overageUnitMinor;

    /** Price per 1M AI tokens above the allowance. NULL = hard cap instead of overage. */
    @Column(name = "ai_overage_per_million_minor")
    private Long aiOveragePerMillionMinor;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}
