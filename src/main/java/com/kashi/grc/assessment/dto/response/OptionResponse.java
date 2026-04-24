package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptionResponse {
    private Long optionId;
    private String optionValue;
    private Double score;
    private boolean isGlobal;
}
