package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * What a subscription's contract value is made of — the base plan, add-on
 * modules, extra vendor packs, onboarding services, discounts.
 *
 * This is the data behind packaging decisions: "which modules actually earn"
 * cannot be answered from a single contract-value number.
 *
 * Amounts are per-term totals in the subscription's currency. A DISCOUNT line
 * carries a negative unitPriceMinor.
 */
@Entity
@Table(name = "subscription_lines",
        indexes = @Index(name = "idx_subline_sub", columnList = "subscription_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class SubscriptionLine extends BaseEntity {

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    /** PLAN | ADDON | VENDOR_PACK | AI_CREDITS | SERVICE | DISCOUNT */
    @Column(name = "line_type", nullable = false, length = 20)
    private String lineType;

    /** Plan code, feature flag key, or service SKU. */
    @Column(name = "item_code", length = 100)
    private String itemCode;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(name = "unit_price_minor", nullable = false)
    private Long unitPriceMinor;

    /** Recurring lines count toward MRR; one-off services (onboarding) do not. */
    @Column(name = "is_recurring", nullable = false)
    @Builder.Default
    private boolean isRecurring = true;
}
