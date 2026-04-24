package com.kashi.grc.workflow.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;

@Data
public class WorkflowCreateRequest {
    @NotBlank public String name;
    @NotBlank public String entityType;
    public String description;
    /** Steps are required at creation time — no orphan blueprints */
    @NotEmpty @Valid public List<WorkflowStepRequest> steps;
}
