package com.kashi.grc.tenant.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "tenants")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class Tenant extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", unique = true, nullable = false)
    private String code;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status")
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "plan")
    private String plan;

    @Column(name = "max_users")
    private Integer maxUsers;

    @Column(name = "max_vendors")
    private Integer maxVendors;

    /**
     * Marks this tenant as an audit firm.
     *
     * A firm is an ordinary tenant in every other respect — its own users, its
     * own compliance posture, its own vendors. The flag exists so a client
     * inviting an external auditor can be offered real firms to pick from, and
     * so the platform-admin list distinguishes them from customers.
     *
     * It deliberately does NOT change how the firm's own tenant behaves. Any
     * future firm-only UI should hang off a feature flag, which is already
     * tenant-scoped, rather than off this column.
     *
     * WRITTEN ONLY BY AuditFirmVerificationService — on approval (true) and on
     * revocation or expiry (false). No request writes it directly, so it cannot
     * be set by a toggle. See audit_firm_applications for the evidence behind
     * every true value.
     */
    @Column(name = "is_audit_firm", nullable = false)
    @Builder.Default
    private boolean isAuditFirm = false;

    // ── COMMERCIAL PROFILE ────────────────────────────────────────────────
    // Four of these cost nothing to capture now and cannot be backfilled later:
    // a year after a customer arrives nobody remembers which channel brought
    // them. Without them every metric is one blended average with nothing to
    // segment by, and "which segment retains" is unanswerable.

    /**
     * Where this customer came from. Same vocabulary as acquisition_costs.channel
     * so CAC can be computed per channel:
     * OUTBOUND | INBOUND | REFERRAL | PARTNER | AUDIT_FIRM_CHANNEL |
     * VENDOR_CHANNEL | EVENTS | PAID_ADS | OTHER
     */
    @Column(name = "acquisition_channel", length = 30)
    private String acquisitionChannel;

    /**
     * First day of the first paid (non-trial, non-zero) subscription. Written
     * once by SubscriptionService and never moved — it anchors cohorts.
     */
    @Column(name = "first_paid_at")
    private LocalDate firstPaidAt;

    /** BFSI | FINTECH | HEALTHCARE | SAAS | IT_SERVICES | MANUFACTURING | GOVERNMENT | AUDIT_ADVISORY | OTHER */
    @Column(name = "industry", length = 40)
    private String industry;

    /** 1-50 | 51-200 | 201-1000 | 1001-5000 | 5000+ */
    @Column(name = "employee_band", length = 20)
    private String employeeBand;

    /** ISO 3166-1 alpha-2, e.g. IN. Decides tax treatment on invoices. */
    @Column(name = "country", length = 2)
    private String country;

    // ── BILL-TO ──────────────────────────────────────────────────────────
    // Copied onto each invoice when it is issued, so editing these never
    // changes an invoice already sent.

    @Column(name = "legal_name", length = 255)
    private String legalName;

    @Column(name = "billing_email", length = 255)
    private String billingEmail;

    @Column(name = "billing_address", columnDefinition = "TEXT")
    private String billingAddress;

    /** GSTIN for Indian customers, VAT/EIN elsewhere. */
    @Column(name = "tax_id", length = 30)
    private String taxId;
}
