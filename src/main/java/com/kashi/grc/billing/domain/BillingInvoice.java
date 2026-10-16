package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An invoice raised against a subscription.
 *
 * Status: DRAFT → ISSUED → PAID, or → VOID. OVERDUE is not stored; it is
 * ISSUED with dueDate in the past, computed on read so it can never be stale.
 *
 * Tax is carried as a rate in basis points plus a label (e.g. 1800 / "IGST 18%")
 * rather than separate CGST/SGST/IGST columns, so an export customer (zero
 * rated, LUT) and a domestic one use the same row. The GSTIN of the buyer is
 * snapshotted onto the invoice — an invoice must not change if the tenant
 * later edits its profile.
 *
 * This is a ledger of what was billed, not a payment processor. Collecting
 * money (Razorpay / Stripe) attaches to it later via paymentReference.
 */
@Entity
@Table(name = "billing_invoices",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoice_number", columnNames = "invoice_number"),
        indexes = {
                @Index(name = "idx_inv_tenant", columnList = "tenant_id"),
                @Index(name = "idx_inv_status", columnList = "status"),
                @Index(name = "idx_inv_sub",    columnList = "subscription_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class BillingInvoice extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "subscription_id")
    private Long subscriptionId;

    /** Assigned on ISSUE, not on draft, so numbering has no gaps. e.g. KG/2026-27/0001 */
    @Column(name = "invoice_number", length = 40)
    private String invoiceNumber;

    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "subtotal_minor", nullable = false)
    @Builder.Default
    private Long subtotalMinor = 0L;

    @Column(name = "tax_rate_bp")
    @Builder.Default
    private Integer taxRateBp = 0;

    @Column(name = "tax_label", length = 60)
    private String taxLabel;

    @Column(name = "tax_minor", nullable = false)
    @Builder.Default
    private Long taxMinor = 0L;

    @Column(name = "total_minor", nullable = false)
    @Builder.Default
    private Long totalMinor = 0L;

    @Column(name = "bill_to_name", length = 255)
    private String billToName;

    @Column(name = "bill_to_address", columnDefinition = "TEXT")
    private String billToAddress;

    @Column(name = "bill_to_tax_id", length = 30)
    private String billToTaxId;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "payment_reference", length = 120)
    private String paymentReference;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;
}
