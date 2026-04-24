package com.kashi.grc.workflow.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One compound-task section within a WorkflowStepRequest.
 *
 * Gap 1+2 fix: WorkflowStepRequest.sections now carries this DTO.
 * saveSteps() and upsertSteps() persist it into workflow_step_sections.
 *
 * At runtime the engine reads workflow_step_sections ONCE per task —
 * inside snapshotSectionsForTask() — and copies every field into
 * task_section_completions.snap_* columns. After that the blueprint row
 * is never read again; running instances are 100% isolated.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StepSectionRequest {

    /** Present on update so upsertSteps can match the existing row. Null on create. */
    public Long id;

    /**
     * Machine-readable key, unique within the step.
     * e.g. "ANSWER", "UPLOAD", "REVIEW", "EVALUATE"
     * Must be SCREAMING_SNAKE_CASE — stored and compared verbatim at runtime.
     */
    @NotBlank
    public String sectionKey;

    /** Display order within the step (1-based). Defaults to insertion order if omitted. */
    public Integer sectionOrder;

    /** Human-readable label shown in the CompoundTaskProgress bar. */
    @NotBlank
    public String label;

    /** Optional longer description shown in the section panel header. */
    public String description;

    /**
     * If true (default), the task cannot be approved until this section completes.
     * If false, the section is shown in progress but does not gate approval.
     */
    public boolean required = true;

    /**
     * The string a module publishes in TaskSectionEvent.completionEvent to mark
     * this section complete at runtime.
     * e.g. "ASSESSMENT_SUBMITTED", "DOCUMENT_UPLOADED", "EVALUATION_SAVED",
     *      "POLICY_SIGNED_OFF", "CONTROL_EVALUATED"
     *
     * Must be unique within the step — the engine matches it 1:1 against
     * snap_completion_event in task_section_completions.
     */
    @NotBlank
    public String completionEvent;

    /**
     * Case 2: when true, this section distributes work to other users.
     * Enables CompoundTaskController.assignSection() for this section.
     */
    public boolean requiresAssignment = false;

    /**
     * Case 3: when true, this section tracks individual items (controls,
     * questions, evidence). Enables registerItems() / completeItem().
     */
    public boolean tracksItems = false;
}