package com.kashi.grc.assessment.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OptionCreateRequest {
    @NotBlank public String optionValue;
    public Double score;
    public Boolean isGlobal;
}
