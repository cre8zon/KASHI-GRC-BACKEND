package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Immutable month-end picture of one tenant. Written once by MrrSnapshotJob.
 *
 * Retention, expansion and churn are computed from THESE rows, never re-derived
 * from tenant_subscriptions. The contracts table is current truth and gets
 * corrected (a price fixed, a date backdated); the snapshot is history and
 * does not move. A retention number that changes when you look at it again is
 * worse than none.
 *
 * FX is frozen here too: mrrReportingMinor is converted at the rate in force
 * when the snapshot was written, so a currency move next year does not rewrite
 * last year's NRR.
 */
@Entity
@Table(name = "tenant_mrr_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_mrr_tenant_month",
                columnNames = {"tenant_id", "period_month"}),
        indexes = @Index(name = "idx_mrr_month", columnList = "period_month"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TenantMrrSnapshot extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** YYYY-MM */
    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "plan_code", length = 50)
    private String planCode;

    /** Subscription status at month end, or NONE. */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "mrr_minor", nullable = false)
    @Builder.Default
    private Long mrrMinor = 0L;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    /** MRR converted to the reporting currency at snapshot time. */
    @Column(name = "mrr_reporting_minor", nullable = false)
    @Builder.Default
    private Long mrrReportingMinor = 0L;

    @Column(name = "reporting_currency", nullable = false, length = 3)
    @Builder.Default
    private String reportingCurrency = "INR";

    /** Users who logged in during the month. */
    @Column(name = "seats_active")
    private Integer seatsActive;

    @Column(name = "seats_purchased")
    private Integer seatsPurchased;

    @Column(name = "vendors_active")
    private Integer vendorsActive;

    @Column(name = "vendors_purchased")
    private Integer vendorsPurchased;

    /** AI inference cost for the month, micros of USD as AiUsageCounter records it. */
    @Column(name = "ai_cost_micros")
    private Long aiCostMicros;
}
