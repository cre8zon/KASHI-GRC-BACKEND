package com.kashi.grc.assessment.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SectionQuestionRequest {
    @NotNull public Long questionId;
    public Double weight;
    public Boolean isMandatory;
    public Integer orderNo;
}
