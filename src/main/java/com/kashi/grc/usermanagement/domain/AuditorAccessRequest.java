package com.kashi.grc.usermanagement.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An audit firm asking a client for access.
 *
 * A request grants nothing. It carries the firm's proposal — and a decision the
 * client still has to make. Approving it creates the same FirmAccessGrant the
 * client-initiated path creates, so the rule that the client decides who may
 * work in their tenant is untouched; this only changes who starts the
 * conversation.
 *
 * That matters because in practice the firm usually knows first: the engagement
 * letter is signed and the audit starts Monday, while the client admin has not
 * yet thought about admitting anyone.
 */
@Entity
@Table(name = "auditor_access_requests",
        indexes = {
                @Index(name = "idx_aar_client", columnList = "client_tenant_id,status"),
                @Index(name = "idx_aar_firm",   columnList = "firm_tenant_id,status")
        })
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AuditorAccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firm_tenant_id", nullable = false)
    private Long firmTenantId;

    @Column(name = "client_tenant_id", nullable = false)
    private Long clientTenantId;

    /** PENDING | APPROVED | DECLINED | WITHDRAWN */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /**
     * What the firm proposes as an end date. Advisory only — the grant takes the
     * client's value. A firm suggesting its own access window and having it
     * honoured silently would invert the control.
     */
    @Column(name = "requested_until")
    private LocalDateTime requestedUntil;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Shown to the firm on decline, so a refusal is not silent. */
    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "grant_id")
    private Long grantId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    public boolean isPending() {
        return "PENDING".equalsIgnoreCase(status);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}