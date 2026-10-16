package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Sales and marketing spend for one month and one channel — the numerator of CAC.
 *
 * Fully loaded: salaries, commissions, ads, events, tooling AND founder time
 * spent selling (enter it at an honest hourly value). A CAC that counts ad
 * spend alone makes every channel look profitable.
 *
 * channel uses the same vocabulary as tenants.acquisition_channel so CAC can be
 * computed per channel against the customers that channel actually won.
 */
@Entity
@Table(name = "acquisition_costs",
        indexes = @Index(name = "idx_acq_month", columnList = "period_month"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AcquisitionCost extends BaseEntity {

    /** YYYY-MM */
    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    /** OUTBOUND | INBOUND | REFERRAL | PARTNER | AUDIT_FIRM_CHANNEL | VENDOR_CHANNEL | EVENTS | PAID_ADS | OTHER */
    @Column(name = "channel", nullable = false, length = 30)
    private String channel;

    /** SALARY | COMMISSION | ADS | EVENTS | TOOLING | FOUNDER_TIME | AGENCY | OTHER */
    @Column(name = "cost_type", length = 20)
    private String costType;

    @Column(name = "spend_minor", nullable = false)
    private Long spendMinor;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;
}
