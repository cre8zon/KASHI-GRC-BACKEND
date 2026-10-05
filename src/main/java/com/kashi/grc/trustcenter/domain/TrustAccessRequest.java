package com.kashi.grc.trustcenter.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * The prospect, the gate and the evidence — one row.
 *
 * Most trust centers have a "request access" form and, separately, a CRM
 * integration for leads. That is one object described twice: the person
 * identifying themselves to get the SOC 2 report IS the lead, and this row is
 * also the record of who received it.
 *
 * ── THE TOKEN IS NOT A SIGNED STORAGE URL ─────────────────────────────────
 * The obvious build is a pre-signed S3 link emailed on approval. That link then
 * lives in the prospect's inbox, gets forwarded, and works for anyone holding
 * it until it expires. A token tied to this row is checked server-side on every
 * fetch, can be revoked the moment a deal dies, and is counted. More work; it
 * is the difference between controlling the document and hoping.
 */
@Entity
@Table(
        name = "trust_access_requests",
        uniqueConstraints = @UniqueConstraint(name = "uq_trust_token", columnNames = "access_token"),
        indexes = {
            @Index(name = "idx_tar_center", columnList = "tenant_id,trust_center_id,status"),
            @Index(name = "idx_tar_email",  columnList = "tenant_id,requester_email"),
        })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TrustAccessRequest extends TenantAwareEntity {

    @Column(name = "trust_center_id", nullable = false) private Long trustCenterId;

    /** NULL means "access to everything gated", which is what most people want. */
    @Column(name = "trust_document_id") private Long trustDocumentId;

    @Column(name = "requester_email", nullable = false, length = 200) private String requesterEmail;
    @Column(name = "requester_name", length = 200) private String requesterName;
    @Column(length = 200) private String company;
    @Column(name = "job_title", length = 150) private String jobTitle;
    @Column(length = 1000) private String purpose;

    /** Recorded, not enforced. Ticking a box is not an NDA — what this gives
     *  you is the date and the claim, which is what matters if it is disputed. */
    @Column(name = "nda_acknowledged", nullable = false)
    @Builder.Default
    private boolean ndaAcknowledged = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "access_token", length = 80) private String accessToken;
    @Column(name = "token_expires_at") private LocalDateTime tokenExpiresAt;

    @Column(name = "approved_by") private Long approvedBy;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "denied_reason", length = 500) private String deniedReason;
    @Column(name = "revoked_at") private LocalDateTime revokedAt;

    /** A dispute about who downloaded what is won or lost on these. */
    @Column(name = "source_ip", length = 64) private String sourceIp;
    @Column(name = "user_agent", length = 400) private String userAgent;

    public enum Status { PENDING, APPROVED, DENIED, REVOKED, EXPIRED }

    @Transient
    public boolean isUsable() {
        return status == Status.APPROVED
                && accessToken != null
                && tokenExpiresAt != null
                && tokenExpiresAt.isAfter(LocalDateTime.now());
    }
}
