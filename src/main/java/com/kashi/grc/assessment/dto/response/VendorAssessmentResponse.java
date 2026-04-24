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
    private String templateName; // still populated from snapshot
    private String status;
    private LocalDateTime submittedAt;
    private Map<String, Object> progress;
    private List<SectionInstanceResponse> sections;

    // Report summary fields — populated by /review endpoint so CISO can see
    // risk rating and open remediations in the report panel before sign-off
    private String  riskRating;
    private Integer openRemediationCount;
}