package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One contract term for one tenant. The source of truth for every money metric.
 *
 * ── THE CHAIN ─────────────────────────────────────────────────────────────
 * A renewal, an upgrade and a downgrade are each a NEW row whose
 * previousSubscriptionId points at the one it replaced. Rows are never edited
 * into a different contract. That chain is what lets NRR say "this revenue came
 * from a customer who was already here" — reconstructing it later from dates is
 * guesswork.
 *
 * ── STATUS ────────────────────────────────────────────────────────────────
 *   TRIAL      in a free trial, contributes 0 MRR
 *   ACTIVE     live, contributes MRR
 *   PAST_DUE   live but an invoice is overdue — still counts as MRR
 *   REPLACED   superseded by a renewal / plan change (chain continues)
 *   CANCELLED  will not renew; still live until termEnd
 *   CHURNED    ended without a successor. churnedAt is set by ONE code path:
 *              SubscriptionService.markChurned — never by hand.
 *
 * Deliberately NOT tenant-aware (no TenantAwareEntity): this table is only
 * ever read by the platform, across tenants, behind the SIDE_SYSTEM lock.
 */
@Entity
@Table(name = "tenant_subscriptions",
        indexes = {
                @Index(name = "idx_tsub_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_tsub_status",  columnList = "status"),
                @Index(name = "idx_tsub_term_end", columnList = "term_end"),
                @Index(name = "idx_tsub_prev",    columnList = "previous_subscription_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TenantSubscription extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "plan_code", nullable = false, length = 50)
    private String planCode;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "term_start", nullable = false)
    private LocalDate termStart;

    /** Exclusive end of the paid term. A 12-month term from 2026-04-01 ends 2027-04-01. */
    @Column(name = "term_end", nullable = false)
    private LocalDate termEnd;

    /** NULL = unlimited seats (the recommended default). */
    @Column(name = "seats_purchased")
    private Integer seatsPurchased;

    /** Vendors under management bought — the priced metric. NULL = unlimited. */
    @Column(name = "vendors_purchased")
    private Integer vendorsPurchased;

    /** Total contract value for the whole term, minor units. 0 for trials. */
    @Column(name = "contract_value_minor", nullable = false)
    @Builder.Default
    private Long contractValueMinor = 0L;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    /** MONTHLY | QUARTERLY | ANNUAL | MULTI_YEAR — how it is invoiced, not how long it runs. */
    @Column(name = "billing_interval", nullable = false, length = 12)
    @Builder.Default
    private String billingInterval = "ANNUAL";

    @Column(name = "auto_renew", nullable = false)
    @Builder.Default
    private boolean autoRenew = true;

    /** SELF_SERVE | SALES_LED | PARTNER | AUDIT_FIRM_CHANNEL | VENDOR_CHANNEL */
    @Column(name = "sales_motion", length = 30)
    private String salesMotion;

    /** NEW | RENEWAL | UPGRADE | DOWNGRADE | REACTIVATION */
    @Column(name = "change_type", length = 20)
    @Builder.Default
    private String changeType = "NEW";

    @Column(name = "previous_subscription_id")
    private Long previousSubscriptionId;

    @Column(name = "churned_at")
    private LocalDate churnedAt;

    /** PRICE | NO_ADOPTION | COMPETITOR | BUDGET | ACQUIRED | OUT_OF_BUSINESS | OTHER */
    @Column(name = "churn_reason", length = 30)
    private String churnReason;

    @Column(name = "churn_note", columnDefinition = "TEXT")
    private String churnNote;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** Purchase order / signed order form reference. */
    @Column(name = "po_reference", length = 120)
    private String poReference;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;
}
