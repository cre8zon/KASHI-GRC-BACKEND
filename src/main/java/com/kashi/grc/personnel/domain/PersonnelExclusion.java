package com.kashi.grc.personnel.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A deliberate carve-out from one compliance requirement for one person.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────
 * Without it, a contractor who genuinely needs no background check shows
 * permanently red on the roster — and a register that is permanently red is
 * one nobody reads. The colour stops meaning anything, which is worse than not
 * colouring it at all.
 *
 * An exclusion is a decision on the record: it has an owner, a written reason
 * and, ideally, an expiry. That is the difference between "we decided this
 * does not apply to her" and "nobody got round to it".
 *
 * ── EXPIRY IS ENCOURAGED, NOT REQUIRED ────────────────────────────────────
 * expiresAt NULL means indefinite, which is sometimes right. But an expiring
 * exclusion forces the decision to be re-made rather than inherited by whoever
 * runs the programme in three years, so the form says so.
 */
@Entity
@Table(
        name = "personnel_exclusions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_personnel_requirement",
                                  columnNames = {"personnel_id", "requirement_key"})
        },
        indexes = {
                @Index(name = "idx_pex_personnel", columnList = "personnel_id"),
                @Index(name = "idx_pex_tenant",    columnList = "tenant_id,is_active"),
                @Index(name = "idx_pex_expiry",    columnList = "expires_at"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PersonnelExclusion extends TenantAwareEntity {

    @Column(name = "personnel_id", nullable = false)
    private Long personnelId;

    /**
     * BACKGROUND_CHECK / NDA / ACCEPTABLE_USE / CODE_OF_CONDUCT / TRAINING /
     * DEVICE_COMPLIANCE. The last two are in the vocabulary ahead of the
     * modules that will use them, so it does not churn later.
     */
    @Column(name = "requirement_key", nullable = false, length = 50)
    private String requirementKey;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "excluded_by")
    private Long excludedBy;

    @Column(name = "excluded_at", nullable = false)
    private LocalDateTime excludedAt;

    /** NULL = indefinite. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * Active AND unexpired. An expired exclusion stops suppressing the
     * requirement without anyone having to run a sweep — the row stays as the
     * record that the decision was once made.
     */
    @Transient
    public boolean isEffective() {
        if (!isActive) return false;
        return expiresAt == null || expiresAt.isAfter(LocalDateTime.now());
    }
}
