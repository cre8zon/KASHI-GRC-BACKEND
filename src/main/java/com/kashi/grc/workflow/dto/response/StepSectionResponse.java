package com.kashi.grc.workflow.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Blueprint section descriptor returned inside WorkflowStepResponse.sections[].
 * Populated from workflow_step_sections by buildWorkflowResponse().
 *
 * Gap 1+2 fix: enables the admin UI (StepForm + StepSectionEditor) to
 * read back sections that were saved with WorkflowStepRequest.sections.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StepSectionResponse {
    private Long    id;
    private String  sectionKey;
    private Integer sectionOrder;
    private String  label;
    private String  description;
    private boolean required;
    private String  completionEvent;
    private boolean requiresAssignment;
    private boolean tracksItems;
}