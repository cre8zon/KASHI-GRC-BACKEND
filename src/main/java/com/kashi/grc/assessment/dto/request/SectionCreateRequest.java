package com.kashi.grc.assessment.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SectionCreateRequest {
    @NotBlank public String name;
    public Integer orderNo;
}
