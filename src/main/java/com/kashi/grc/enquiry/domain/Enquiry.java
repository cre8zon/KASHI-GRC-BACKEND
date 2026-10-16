package com.kashi.grc.enquiry.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Anything a person asks for from the public website: a demo, a trial, a
 * sales conversation, a support request, an audit firm wanting to partner.
 *
 * ── ONE TABLE, NOT ONE PER FORM ──────────────────────────────────────────────
 * The forms differ by a few fields; the work does not. Every enquiry is read,
 * owned, answered and closed, and the sales ones end in a tenant. One inbox
 * with a type column is what a two-person team can actually keep on top of,
 * and it is what makes "how many demo requests became customers, by source"
 * a single query.
 *
 * ── PLATFORM-OWNED ───────────────────────────────────────────────────────────
 * No tenant_id: these are people who are not customers yet. The tenant links
 * are named so nothing mistakes them for a tenant scope:
 *   convertedTenantId  the tenant this enquiry became (sales types)
 *   matchedTenantId    an existing customer, matched by the sender's email
 *                      (mostly support requests)
 *
 * ── PRIVACY ──────────────────────────────────────────────────────────────────
 * The sender's IP is stored only as a salted SHA-256 (for rate limiting and
 * abuse review), never raw. Marketing consent is recorded separately from the
 * request itself — asking for a demo is not consent to a newsletter.
 */
@Entity
@Table(name = "enquiries", indexes = {
        @Index(name = "ix_enquiry_status_type", columnList = "status, enquiry_type"),
        @Index(name = "ix_enquiry_email", columnList = "email"),
        @Index(name = "ix_enquiry_created", columnList = "created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_enquiry_reference", columnNames = "reference"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class Enquiry extends BaseEntity {

    /** Short human reference quoted in emails, e.g. KG-7F3K2A. */
    @Column(name = "reference", nullable = false, length = 16)
    private String reference;

    /** DEMO | TRIAL | CONTACT_SALES | AUDIT_FIRM | PARTNERSHIP | SUPPORT | GENERAL */
    @Column(name = "enquiry_type", nullable = false, length = 20)
    private String enquiryType;

    /** NEW | IN_PROGRESS | QUALIFIED | CONVERTED | CLOSED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "NEW";

    /** LOW | NORMAL | HIGH */
    @Column(name = "priority", nullable = false, length = 10)
    @Builder.Default
    private String priority = "NORMAL";

    // ── who ──────────────────────────────────────────────────────────────────
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "company", length = 200)
    private String company;

    @Column(name = "job_title", length = 150)
    private String jobTitle;

    /** ISO 3166-1 alpha-2 when known. */
    @Column(name = "country", length = 2)
    private String country;

    /** Same vocabulary as tenants.employee_band: 1-50 … 5000+. */
    @Column(name = "employee_band", length = 20)
    private String employeeBand;

    /** Rough count of third parties they assess (demo form). */
    @Column(name = "vendor_count")
    private Integer vendorCount;

    // ── what ─────────────────────────────────────────────────────────────────
    /** Plan the visitor clicked, by code. Kept even if the plan is later retired. */
    @Column(name = "plan_code", length = 50)
    private String planCode;

    /** Free-text topic from the form's "most interested in" select. */
    @Column(name = "topic", length = 60)
    private String topic;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /** "How did you hear about us?" as typed. */
    @Column(name = "heard_about", length = 255)
    private String heardAbout;

    // ── where from (first touch, captured by the website) ────────────────────
    @Column(name = "source_page", length = 512)
    private String sourcePage;

    @Column(name = "landing_page", length = 512)
    private String landingPage;

    @Column(name = "referrer", length = 512)
    private String referrer;

    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "utm_medium", length = 100)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 150)
    private String utmCampaign;

    @Column(name = "utm_term", length = 150)
    private String utmTerm;

    @Column(name = "utm_content", length = 150)
    private String utmContent;

    /** ?ref= on any site URL — an audit firm's tenant code sharing its link. */
    @Column(name = "referral_code", length = 50)
    private String referralCode;

    // ── consent & abuse ──────────────────────────────────────────────────────
    @Column(name = "marketing_consent", nullable = false)
    @Builder.Default
    private boolean marketingConsent = false;

    @Column(name = "consent_at")
    private LocalDateTime consentAt;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** An open enquiry from the same email already existed when this arrived. */
    @Column(name = "duplicate_of_id")
    private Long duplicateOfId;

    // ── handling ─────────────────────────────────────────────────────────────
    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "first_response_at")
    private LocalDateTime firstResponseAt;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    /** NOT_A_FIT | NO_RESPONSE | DUPLICATE | SPAM | RESOLVED | LOST_TO_COMPETITOR | BUDGET | OTHER */
    @Column(name = "closed_reason", length = 30)
    private String closedReason;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "matched_tenant_id")
    private Long matchedTenantId;

    @Column(name = "converted_tenant_id")
    private Long convertedTenantId;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "converted_by")
    private Long convertedBy;
}
