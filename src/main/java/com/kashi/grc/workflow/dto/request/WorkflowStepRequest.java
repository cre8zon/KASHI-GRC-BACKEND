package com.kashi.grc.workflow.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kashi.grc.workflow.enums.ApprovalType;
import com.kashi.grc.workflow.enums.StepAction;
import com.kashi.grc.workflow.enums.AssignerResolution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

/**
 * Full replacement for WorkflowStepRequest.java.
 *
 * Gap 1+2 fix: added List<StepSectionRequest> sections at the bottom.
 * All other fields are identical to the existing file.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowStepRequest {

    /** Existing step ID — present on update, null on create. Enables upsert in updateWorkflow. */
    public Long id;

    @NotBlank
    public String name;
    public String description;

    @NotNull @Min(1)
    public Integer stepOrder;
    public String side;

    @NotNull
    public ApprovalType approvalType;
    public Integer minApprovalsRequired;
    public Boolean isParallel;
    public Boolean isOptional;
    public Integer slaHours;

    /** Actor roles — who DOES the work (workflow_step_actor_roles) */
    public List<Long> roleIds;

    /** Direct user assignments */
    public List<Long> userIds;

    /** SYSTEM steps: key of the AutomatedActionHandler */
    public String automatedAction;

    /** POOL | PUSH_TO_ROLES | PREVIOUS_ACTOR | INITIATOR */
    public AssignerResolution assignerResolution;

    /** Assigner roles for PUSH_TO_ROLES — any side (workflow_step_assigner_roles) */
    public List<Long> assignerRoleIds;

    /** If true, assigner can redirect task to a specific person */
    public Boolean allowOverride;

    /** What the actor does: ASSIGN|FILL|REVIEW|APPROVE|ACKNOWLEDGE|EVALUATE|GENERATE|CUSTOM */
    public StepAction stepAction;

    /** Observer roles — read-only access, no task, any side */
    public List<Long> observerRoleIds;

    /**
     * Nav table key for ACTOR tasks — which page the actor lands on.
     * Must match a navKey in ui_navigation. e.g. "vendor_assessment_fill".
     * Null for SYSTEM steps.
     */
    public String navKey;

    /**
     * Nav table key for ASSIGNER tasks — which page the coordinator lands on.
     * Must match a navKey in ui_navigation. e.g. "vendor_assessment_assign".
     * Null for SYSTEM steps and steps with no assignerRoles.
     */
    public String assignerNavKey;

    // ── Gap 1+2 addition ─────────────────────────────────────────────────────
    /**
     * Compound task sections for this step.
     *
     * Each section declares one piece of work that must be done before the
     * task can be approved. The engine reads these ONCE at step activation
     * (snapshotSectionsForTask) and copies them into task_section_completions
     * snap_* columns. After that the blueprint row is never read again —
     * running instances are fully isolated from blueprint changes.
     *
     * Null or empty = no compound task gate. The actor approves directly
     * from the inbox without completing any sub-sections.
     *
     * At runtime, modules complete sections by publishing:
     *   eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
     *       "YOUR_COMPLETION_EVENT", taskId, userId));
     *
     * The completionEvent string on each section must match the string
     * the module publishes — that is the only coupling between blueprint and module.
     */
    public List<StepSectionRequest> sections;
}