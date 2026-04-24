package com.kashi.grc.vendor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RiskMappingResponse {
    private Long mappingId;
    private BigDecimal minScore;
    private BigDecimal maxScore;
    private Long tierId;
    private String tierName;
    private String tierLabel;
    private Long templateId;
}
