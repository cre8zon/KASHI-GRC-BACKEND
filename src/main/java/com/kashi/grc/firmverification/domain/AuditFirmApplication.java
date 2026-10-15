package com.kashi.grc.firmverification.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A tenant's request to be recognised as an audit firm — and the evidence and
 * decision behind every tenants.is_audit_firm = true.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────
 * The flag lets a tenant's people be admitted into OTHER customers' tenants as
 * external auditors. It was a checkbox on Create Tenant: one click, no record
 * of why, no expiry. For a GRC product whose customers will ask "how do you
 * know that firm is a real auditor", that is not an answer.
 *
 * ── WHO DRIVES IT ─────────────────────────────────────────────────────────
 * Platform users only. The firm sends its details and evidence to the
 * platform by email; a platform user records them here, uploads the evidence,
 * and the system mails the firm's contact a one-time confirmation link. There
 * is no tenant-side screen — audit-firm status is not something every tenant
 * should see in its settings, or be able to ask for itself.
 *
 * ── STATUS ────────────────────────────────────────────────────────────────
 *   DRAFT           being prepared by a platform user
 *   SUBMITTED       evidence complete, waiting for a reviewer
 *   UNDER_REVIEW    a reviewer has picked it up
 *   INFO_REQUESTED  reviewer needs more from the firm; editable again
 *   APPROVED        flag on, until verifiedUntil
 *   REJECTED        closed; a new application may be started
 *   REVOKED         was approved, withdrawn by the platform; flag off
 *   EXPIRED         verifiedUntil passed without re-verification; flag off
 *
 * One open application per tenant (DRAFT..INFO_REQUESTED). An APPROVED one may
 * have a renewal application open beside it in its last 60 days.
 */
@Entity
@Table(name = "audit_firm_applications",
        indexes = {
                @Index(name = "idx_afa_tenant", columnList = "tenant_id"),
                @Index(name = "idx_afa_status", columnList = "status")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditFirmApplication extends BaseEntity {

    /** The firm's own tenant. */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    // ── WHO THE FIRM SAYS IT IS ───────────────────────────────────────────

    @Column(name = "firm_legal_name", length = 255)
    private String firmLegalName;

    /** CA_FIRM | CPA_FIRM | CERTIFICATION_BODY | CERT_IN_EMPANELLED | PCI_QSA | INTERNAL_AUDIT_PRACTICE | OTHER */
    @Column(name = "firm_type", length = 30)
    private String firmType;

    /** Who registered / accredited it: ICAI, AICPA, PCAOB, NABCB, ANAB, UKAS, CERT-In, PCI SSC … */
    @Column(name = "registration_body", length = 60)
    private String registrationBody;

    /** ICAI firm registration number, CPA licence, accreditation certificate number … */
    @Column(name = "registration_number", length = 80)
    private String registrationNumber;

    /** Date the registration / accreditation lapses, if it has one. Caps verifiedUntil. */
    @Column(name = "registration_valid_until")
    private LocalDate registrationValidUntil;

    /** ISO 3166-1 alpha-2 */
    @Column(name = "jurisdiction", length = 2)
    private String jurisdiction;

    @Column(name = "website", length = 255)
    private String website;

    /** Derived from website; the domain that must carry the DNS TXT record. */
    @Column(name = "domain", length = 255)
    private String domain;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_designation", length = 120)
    private String contactDesignation;

    /** Frameworks the firm audits: SOC2,ISO27001,PCI_DSS,ISO27701,HIPAA,RBI,SEBI_CSCRF,DPDPA … */
    @Column(name = "frameworks_csv", length = 500)
    private String frameworksCsv;

    @Column(name = "partner_count")
    private Integer partnerCount;

    /** Free text: anything the firm wants the reviewer to know. */
    @Column(name = "statement", columnDefinition = "TEXT")
    private String statement;

    /**
     * The firm's authorised signatory confirmed the details are accurate. Set by
     * the email confirmation, which shows them the details they are confirming.
     */
    @Column(name = "attested", nullable = false)
    @Builder.Default
    private boolean attested = false;

    // ── MAILBOX CONTROL ───────────────────────────────────────────────────
    // A one-time link is mailed to contactEmail, which must be on the firm's
    // own website domain. Clicking it proves someone at that firm controls that
    // mailbox. Only a SHA-256 of the token is stored, so a database read cannot
    // be turned into a confirmation.

    @Column(name = "email_token_hash", length = 64)
    private String emailTokenHash;

    @Column(name = "email_sent_at")
    private LocalDateTime emailSentAt;

    @Column(name = "email_sent_to", length = 255)
    private String emailSentTo;

    @Column(name = "email_expires_at")
    private LocalDateTime emailExpiresAt;

    @Column(name = "email_confirmed_at")
    private LocalDateTime emailConfirmedAt;

    @Column(name = "email_confirmed_ip", length = 64)
    private String emailConfirmedIp;

    @Column(name = "email_send_count", nullable = false)
    @Builder.Default
    private int emailSendCount = 0;

    // ── LIFECYCLE ─────────────────────────────────────────────────────────

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "review_started_at")
    private LocalDateTime reviewStartedAt;

    /**
     * Reviewer checklist, JSON: {"registryChecked":true,"registryNote":"ICAI FRN 123456W found",
     * "evidenceAuthentic":true,"domainControl":"DNS"|"WAIVED","domainWaiverNote":"…",
     * "sanctionsChecked":true,"independenceChecked":true}
     */
    @Column(name = "checklist_json", columnDefinition = "TEXT")
    private String checklistJson;

    /** The latest question to the firm, when INFO_REQUESTED. */
    @Column(name = "info_request", columnDefinition = "TEXT")
    private String infoRequest;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "verified_until")
    private LocalDate verifiedUntil;

    @Column(name = "expiry_reminder_sent_at")
    private LocalDateTime expiryReminderSentAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", columnDefinition = "TEXT")
    private String revokeReason;

    /** The application this one re-verifies (renewal chain). */
    @Column(name = "previous_application_id")
    private Long previousApplicationId;
}
