package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vendor_assessments")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VendorAssessment extends TenantAwareEntity {

    @Column(name = "cycle_id", nullable = false)
    private Long cycleId;

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "status")
    @Builder.Default
    private String status = "ASSIGNED";

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "total_possible_score")
    private Double totalPossibleScore;

    @Column(name = "total_earned_score")
    private Double totalEarnedScore;

    @Column(name = "report_version")
    @Builder.Default
    private Integer reportVersion = 0;

    @Column(name = "report_generated_at")
    private LocalDateTime reportGeneratedAt;

    @Column(name = "report_generated_by")
    private Long reportGeneratedBy;

    @Column(name = "report_url", columnDefinition = "TEXT")
    private String reportUrl;

    @Column(name = "risk_rating", length = 20)
    private String riskRating;

    @Column(name = "review_findings", columnDefinition = "TEXT")
    private String reviewFindings;

    /**
     * The one Org CISO who leads the review of THIS assessment.
     *
     * Nominated by the Org Admin at step 9 ("Org Admin Assigns Review to Org
     * CISO") and read back by VendorWorkflowActorResolver, so every org-side
     * CISO step afterwards belongs to that person rather than to the whole CISO
     * pool.
     *
     * ── WHAT IT REPLACES ──────────────────────────────────────────────────
     *
     * Step 10 is ORGANIZATION + ASSIGN, which the resolver matched on neither
     * side-and-action clause, so it returned an empty list and the engine fell
     * back to ROLE_BASED — a task for EVERY Org CISO in the tenant. Step 9's
     * Approve simply advanced the workflow and nominated nobody, because there
     * was nowhere to record a nomination.
     *
     * ── WHY ON THE ASSESSMENT AND NOT THE TASK ────────────────────────────
     *
     * A task is per step. This has to outlive step 10 and still be readable at
     * step 13, so it belongs to the assessment, beside the risk rating and the
     * findings the same review produces.
     *
     * Nullable: every assessment created before this column exists has no lead,
     * and the resolver treats null as "no nomination" and leaves the previous
     * ROLE_BASED behaviour exactly as it was.
     */
    @Column(name = "review_lead_user_id")
    private Long reviewLeadUserId;

    @Column(name = "open_remediation_count")
    @Builder.Default
    private Integer openRemediationCount = 0;
}