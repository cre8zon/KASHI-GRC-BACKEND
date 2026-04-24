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

    @Column(name = "open_remediation_count")
    @Builder.Default
    private Integer openRemediationCount = 0;
}
