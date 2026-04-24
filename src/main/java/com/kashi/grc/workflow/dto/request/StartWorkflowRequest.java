package com.kashi.grc.workflow.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Request DTO for starting a new WorkflowInstance.
 *
 * CHANGED: Added @JsonIgnoreProperties(ignoreUnknown = true).
 *
 * WHY: When the frontend calls the start-workflow endpoint, form state sometimes
 * contains extra keys (e.g. a stale taskInstanceId from a previous action, or
 * additional fields from a shared form object). Spring's default Jackson
 * deserialization fails with a 400 MethodArgumentNotValidException when it
 * encounters unknown fields on a class with @Valid.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) makes Jackson silently discard
 * any JSON keys that do not map to a field in this class, preventing the
 * spurious 400 errors without relaxing the validation on required fields.
 *
 * Required fields are still enforced by @NotNull / @NotBlank as before.
 * No other logic changes.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StartWorkflowRequest {

    /** ID of the active Workflow blueprint to instantiate. Required. */
    @NotNull
    public Long workflowId;

    /** PK of the org's business entity being processed (e.g. vendorId). Required. */
    @NotNull
    public Long entityId;

    /**
     * Type of the entity — must match the workflow's entityType.
     * e.g. "VENDOR", "AUDIT", "CONTRACT". Required.
     */
    @NotBlank
    public String entityType;

    /** Instance priority: LOW | MEDIUM | HIGH. Defaults to MEDIUM if null. Optional. */
    public String priority;

    /** Optional deadline for the entire workflow instance. */
    public LocalDateTime dueDate;

    /** Optional free-text remarks recorded at instance creation. */
    public String remarks;
}
