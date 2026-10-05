package com.kashi.grc.accessreview.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One entitlement, one reviewer, one decision — and proof it was carried out.
 *
 * ── revocationStatus IS WHY THIS MODULE EXISTS ────────────────────────────
 * A decision to revoke is not a revocation. Access review tools record RETAIN
 * or REVOKE and mark the campaign complete; nothing checks the revoke actually
 * happened. The result is a signed certification stating access was removed
 * and access that is still there, which is worse than no review at all because
 * the evidence is now wrong.
 *
 * REVOKE sets PENDING here, and the campaign cannot complete until every one
 * is CONFIRMED. Same shape as offboarding refusing while an asset is
 * unreturned.
 */
@Entity
@Table(
        name = "access_review_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ari_entitlement",
                columnNames = {"campaign_id", "entitlement_type", "entitlement_id", "subject_user_id"}),
        indexes = {
            @Index(name = "idx_ari_reviewer",   columnList = "tenant_id,reviewer_user_id,decision"),
            @Index(name = "idx_ari_campaign",   columnList = "campaign_id,decision"),
            @Index(name = "idx_ari_revocation", columnList = "campaign_id,revocation_status"),
        })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AccessReviewItem extends TenantAwareEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "subject_user_id")      private Long subjectUserId;
    @Column(name = "subject_personnel_id") private Long subjectPersonnelId;

    /** Snapshotted, so the certification still reads correctly after the person
     *  is renamed, offboarded or deleted. */
    @Column(name = "subject_label", length = 200)
    private String subjectLabel;

    /** ROLE | PERMISSION_OVERRIDE | FIRM_GRANT | ACCOUNT */
    @Column(name = "entitlement_type", nullable = false, length = 30)
    private String entitlementType;

    @Column(name = "entitlement_id")            private Long entitlementId;
    @Column(name = "entitlement_label", length = 250) private String entitlementLabel;

    /** Normally the subject's manager from the roster — which is exactly why a
     *  roster row for every user had to exist before this module was built. */
    @Column(name = "reviewer_user_id")
    private Long reviewerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Decision decision = Decision.PENDING;

    @Column(name = "decision_note", columnDefinition = "TEXT") private String decisionNote;
    @Column(name = "decided_at") private LocalDateTime decidedAt;
    @Column(name = "decided_by") private Long decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_status", nullable = false, length = 20)
    @Builder.Default
    private RevocationStatus revocationStatus = RevocationStatus.NOT_REQUIRED;

    @Column(name = "revocation_confirmed_at") private LocalDateTime revocationConfirmedAt;
    @Column(name = "revocation_confirmed_by") private Long revocationConfirmedBy;
    @Column(name = "revocation_note", columnDefinition = "TEXT") private String revocationNote;

    /**
     * Why this row deserves a closer look.
     *
     * A flat list of 400 entitlements gets rubber-stamped. Flags are what make
     * a reviewer stop on the eleven that matter.
     */
    @Column(name = "flags_json", columnDefinition = "JSON")
    private String flagsJson;

    public enum Decision { PENDING, RETAIN, REVOKE, MODIFY }
    public enum RevocationStatus { NOT_REQUIRED, PENDING, CONFIRMED, FAILED }

    @Transient
    public boolean isBlockingCompletion() {
        return decision == Decision.PENDING
                || revocationStatus == RevocationStatus.PENDING
                || revocationStatus == RevocationStatus.FAILED;
    }
}
