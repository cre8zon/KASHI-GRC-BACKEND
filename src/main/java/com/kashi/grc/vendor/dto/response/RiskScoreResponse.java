package com.kashi.grc.vendor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RiskScoreResponse {
    private Long vendorId;
    private String vendorName;
    private BigDecimal previousRiskScore;
    private BigDecimal newRiskScore;
    private String riskClassification;
    private Map<String, Object> scoreBreakdown;
    private Map<String, Object> recommendedTier;
    private Map<String, Object> recommendedTemplate;
}
