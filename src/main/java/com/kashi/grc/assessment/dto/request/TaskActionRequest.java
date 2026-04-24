package com.kashi.grc.assessment.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TaskActionRequest {
    @NotBlank public String action;
    public String remarks;
    public Long nextAssigneeId;
    public Integer targetStepOrder;
}
