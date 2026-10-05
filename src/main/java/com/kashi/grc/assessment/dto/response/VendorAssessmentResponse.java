package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorAssessmentResponse {
    private Long assessmentId;

    /**
     * Same value as assessmentId, under the name the generic list screen reads.
     * See VendorResponse.id — without it, a row click navigates to
     * /module/vendor_assessment/undefined.
     */
    private Long id;

    /**
     * (earned / possible) x 100, rounded. NULL when there is nothing to measure.
     *
     * Deliberately null rather than 0 when totalPossibleScore is zero or absent:
     * an assessment nobody has answered is UNMEASURED, not 0% compliant, and a
     * progress bar sitting at zero makes an untouched questionnaire look like a
     * failed one. The list renders null as an em dash.
     *
     * The same figure already exists inside `progress` as compliancePct; this
     * exposes it flat so a column can point at it, and rounds the raw score as
     * a side effect.
     */
    private Integer compliancePercent;

    private Long templateInstanceId;
    private Long vendorId;
    private String vendorName;
    private String templateName;
    private String status;
    private Integer cycleNo;
    private Long workflowInstanceId;

    // Scoring
    private Double totalEarnedScore;
    private Double totalPossibleScore;

    // Risk
    private String riskRating;
    private String reviewFindings;

    // Timestamps
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;

    // Report
    private String reportUrl;

    private Map<String, Object> progress;
    private List<SectionInstanceResponse> sections;

    // Report summary fields
    private Integer openRemediationCount;
}