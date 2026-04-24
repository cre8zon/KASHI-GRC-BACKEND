package com.kashi.grc.vendor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorTierResponse {
    private Long tierId;
    private String tierName;
    private Integer assessmentFrequencyMonths;
    private boolean requiresSoc2;
    private boolean requiresIso27001;
    private String description;
    private LocalDateTime createdAt;
}
