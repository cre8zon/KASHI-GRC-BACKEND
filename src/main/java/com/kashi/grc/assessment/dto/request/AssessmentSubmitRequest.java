package com.kashi.grc.assessment.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssessmentSubmitRequest {
    @NotNull public Long taskId;
    public String remarks;
}
