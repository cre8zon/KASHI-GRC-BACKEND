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