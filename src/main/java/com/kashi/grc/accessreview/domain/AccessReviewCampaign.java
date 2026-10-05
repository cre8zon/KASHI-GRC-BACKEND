package com.kashi.grc.accessreview.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One certification cycle.
 *
 * ── PENDING_REMEDIATION IS NOT AN EXTRA STATUS, IT IS THE POINT ───────────
 * Every reviewer has decided, and revocations are outstanding. Without this
 * state a campaign in that condition is either "in progress" — wrong, the
 * reviewing is finished — or "completed", which is wrong and dangerous,
 * because the certification then asserts that access was removed while it is
 * still in place.
 */
@Entity
@Table(
        name = "access_review_campaigns",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_arc_ref", columnNames = {"tenant_id", "campaign_ref"}),
        indexes = @Index(name = "idx_arc_status", columnList = "tenant_id,status,is_deleted"))
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AccessReviewCampaign extends AuditableEntity {

    @Column(name = "campaign_ref", nullable = false, length = 40)
    private String campaignRef;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    /** ALL_USERS | PRIVILEGED_ONLY | EXTERNAL_FIRMS | VENDOR_STAFF | BY_ROLE */
    @Column(name = "scope_type", nullable = false, length = 30)
    @Builder.Default
    private String scopeType = "ALL_USERS";

    @Column(name = "scope_role_id")
    private Long scopeRoleId;

    /** An auditor asks "as at what date". A campaign with no period cannot answer. */
    @Column(name = "period_start") private LocalDate periodStart;
    @Column(name = "period_end")   private LocalDate periodEnd;
    @Column(name = "due_at")       private LocalDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.DRAFT;

    @Column(name = "launched_at")  private LocalDateTime launchedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "completed_by") private Long completedBy;

    /**
     * Frozen at completion.
     *
     * Recomputing the numbers from live rows months later gives a different
     * answer than the one that was signed off — people leave, roles change. The
     * certification has to say what was true when it was made.
     */
    @Column(name = "summary_json", columnDefinition = "JSON")
    private String summaryJson;

    public enum Status { DRAFT, IN_PROGRESS, PENDING_REMEDIATION, COMPLETED, CANCELLED }
}
