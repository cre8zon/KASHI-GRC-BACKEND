package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.time.LocalDateTime;

/**
 * Immutable audit log of every report generated for an assessment.
 *
 * Each time a report is generated (initial, or after remediation closure),
 * a new row is inserted. The assessment.reportVersion is incremented.
 * Nothing is ever updated — this table is append-only.
 *
 * Versioning model:
 *   v1  = initial report (issued at Org CISO final approval — step 11/13)
 *         open_remediation_count may be > 0 (issues still tracked)
 *   v2+ = re-generated after at least one remediation item is closed/accepted
 *         compliance_pct improves if vendor fixed things; same score if accepted-risk
 *
 * report_url: null until PDF generation is implemented.
 *             Stub hook — inject ReportGeneratorService and populate here.
 */
@Entity
@Table(name = "assessment_reports", indexes = {
        @Index(name = "idx_ar_assessment", columnList = "assessment_id, report_version DESC"),
        @Index(name = "idx_ar_tenant",     columnList = "tenant_id, generated_at DESC"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentReport extends TenantAwareEntity {

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "report_version", nullable = false)
    private Integer reportVersion;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "generated_by", nullable = false)
    private Long generatedBy;

    @Column(name = "total_earned_score")
    private Double totalEarnedScore;

    @Column(name = "total_possible_score")
    private Double totalPossibleScore;

    @Column(name = "compliance_pct")
    private Double compliancePct;

    @Column(name = "risk_rating", length = 20)
    private String riskRating;

    /** Snapshot of open remediation items at report time */
    @Column(name = "open_remediation_count")
    @Builder.Default
    private Integer openRemediationCount = 0;

    /** Snapshot of open internal clarification items at report time */
    @Column(name = "open_clarification_count")
    @Builder.Default
    private Integer openClarificationCount = 0;

    @Column(name = "report_url", columnDefinition = "TEXT")
    private String reportUrl;

    /**
     * What triggered this version:
     *   INITIAL            — first report at workflow completion
     *   REMEDIATION_CLOSED — triggered by last open remediation item closing
     *   MANUAL             — reviewer manually re-triggered
     */
    @Column(name = "trigger_event", length = 60)
    private String triggerEvent;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;
}