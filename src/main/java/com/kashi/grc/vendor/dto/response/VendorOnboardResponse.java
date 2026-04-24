package com.kashi.grc.vendor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorOnboardResponse {
    private Long vendorId;
    private Long workflowInstanceId;
    private BigDecimal calculatedRiskScore;
    private Map<String, Object> assignedTemplate;
    private Map<String, Object> currentStep;
}
