package com.kashi.grc.exception_register.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A recorded, time-boxed deviation from a policy, control or configuration.
 *
 * ── THIS IS THE MISSING HALF OF WHAT ALREADY EXISTS ───────────────────────
 * Risk acceptance is already implemented four times in this codebase — Issue,
 * ActionItem and AuditFinding each carry accepted_risk / accepted_risk_by /
 * accepted_risk_note, and Risk has the risk:accept permission.
 *
 * None of the four has an expiry. So every risk accepted in this platform is
 * accepted PERMANENTLY, by whoever happened to be looking, with a note that
 * does not survive them leaving. This class is not a fifth place to accept
 * things; it is the register those four have been missing.
 *
 * Named ExceptionRecord rather than Exception for the obvious reason — the
 * package is exception_register for the same one.
 */
@Entity
@Table(
        name = "exceptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_exception_ref", columnNames = {"tenant_id", "exception_ref"}),
        indexes = {
            @Index(name = "idx_exc_tenant_status", columnList = "tenant_id,status,is_deleted"),
            @Index(name = "idx_exc_expiry",        columnList = "tenant_id,status,expires_at"),
        })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class ExceptionRecord extends AuditableEntity {

    @Column(name = "exception_ref", nullable = false, length = 40)
    private String exceptionRef;

    @Column(nullable = false, length = 250)
    private String title;

    /** POLICY | CONTROL | CONFIGURATION | RISK_ACCEPTANCE | REGULATORY */
    @Column(name = "exception_type", nullable = false, length = 30)
    @Builder.Default
    private String exceptionType = "POLICY";

    @Column(name = "scope_description", columnDefinition = "TEXT")
    private String scopeDescription;

    /** Required. "Approved" with no reason is a decision nobody can defend later. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String justification;

    @Column(name = "compensating_controls", columnDefinition = "TEXT")
    private String compensatingControls;

    /**
     * An explicit admission that nothing compensates.
     *
     * A legitimate answer, recorded as one. What is not acceptable is silence,
     * which is why the service demands one or the other.
     */
    @Column(name = "has_no_compensating_control", nullable = false)
    @Builder.Default
    private boolean hasNoCompensatingControl = false;

    @Column(name = "risk_level", nullable = false, length = 20)
    @Builder.Default
    private String riskLevel = "MEDIUM";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.DRAFT;

    @Column(name = "requested_by")   private Long requestedBy;
    @Column(name = "requested_at")   private LocalDateTime requestedAt;

    /** Never equal to requestedBy. The service refuses it. */
    @Column(name = "approved_by")    private Long approvedBy;
    @Column(name = "approved_at")    private LocalDateTime approvedAt;

    @Column(name = "approval_note", columnDefinition = "TEXT")
    private String approvalNote;

    @Column(name = "rejected_reason", columnDefinition = "TEXT")
    private String rejectedReason;

    /**
     * NOT NULL, and the whole point of the module.
     *
     * An exception with no end date is not an exception; it is an undocumented
     * policy change that nobody will ever revisit.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Defaulted to 30 days before expiry. An exception discovered already
     *  expired is an outage or a finding, not a review. */
    @Column(name = "review_due_at")
    private LocalDateTime reviewDueAt;

    @Column(name = "revoked_at")     private LocalDateTime revokedAt;
    @Column(name = "revoked_by")     private Long revokedBy;
    @Column(name = "revoked_reason", columnDefinition = "TEXT")
    private String revokedReason;

    /** Visible in the list on purpose: an exception on its fifth extension is
     *  not an exception, and only a counter makes that obvious. */
    @Column(name = "renewal_count", nullable = false)
    @Builder.Default
    private Integer renewalCount = 0;

    @Column(name = "superseded_by_id") private Long supersededById;

    @Column(name = "control_tags", length = 500)   private String controlTags;
    @Column(name = "framework_refs", length = 300) private String frameworkRefs;

    public enum Status {
        DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, EXPIRED, REVOKED, CLOSED
    }

    /** Live cover. Only an APPROVED exception that has not passed its date. */
    @Transient
    public boolean isActiveCover() {
        return status == Status.APPROVED
                && expiresAt != null
                && expiresAt.isAfter(LocalDateTime.now());
    }

    @Transient
    public boolean isDueForReview() {
        return status == Status.APPROVED
                && reviewDueAt != null
                && reviewDueAt.isBefore(LocalDateTime.now());
    }
}
