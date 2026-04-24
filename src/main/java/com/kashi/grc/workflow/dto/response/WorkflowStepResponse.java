package com.kashi.grc.workflow.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.workflow.enums.ApprovalType;
import com.kashi.grc.workflow.enums.AssignerResolution;
import com.kashi.grc.workflow.enums.StepAction;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Full replacement for WorkflowStepResponse.java.
 *
 * Gap 1+2 fix: added List<StepSectionResponse> sections at the bottom.
 * All other fields are identical to the existing file.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowStepResponse {
    private Long             id;
    private Long             workflowId;
    private Integer          stepOrder;
    private String           name;
    private String           description;
    private String           side;
    private ApprovalType     approvalType;
    private Integer          minApprovalsRequired;
    private Boolean          isParallel;
    private Boolean          isOptional;
    private Integer          slaHours;
    private String           automatedAction;

    /** Actor roles — who does the work (workflow_step_actor_roles) */
    private List<Long>       roleIds;
    private List<Long>       userIds;

    /** Assignment resolution: POOL | PUSH_TO_ROLES | PREVIOUS_ACTOR | INITIATOR */
    private AssignerResolution assignerResolution;

    /** Assigner roles for PUSH_TO_ROLES — any side (workflow_step_assigner_roles) */
    private List<Long>       assignerRoleIds;
    private Boolean          allowOverride;

    /** What the actor does on this step — drives frontend routing */
    private StepAction       stepAction;

    /** Observer roles — read-only artifact access, no task (workflow_step_observer_roles) */
    private List<Long>       observerRoleIds;

    private String           navKey;
    private String           assignerNavKey;

    // ── Gap 1+2 addition ─────────────────────────────────────────────────────
    /**
     * Compound task sections defined on this blueprint step.
     * Null/empty = simple approve-from-inbox step, no section gate.
     * Populated by buildWorkflowResponse() from workflow_step_sections.
     *
     * The admin UI (StepSectionEditor) reads these to pre-populate
     * the section editor when opening an existing blueprint step.
     */
    private List<StepSectionResponse> sections;
}