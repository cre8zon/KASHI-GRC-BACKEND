package com.kashi.grc.vendor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VendorTierRequest {
    @NotBlank public String tierName;
    public Integer assessmentFrequencyMonths;
    public Boolean requiresSoc2;
    public Boolean requiresIso27001;
    public String description;
}
