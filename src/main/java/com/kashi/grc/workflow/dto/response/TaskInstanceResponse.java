package com.kashi.grc.workflow.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskInstanceResponse {
    // ── Core task fields ──────────────────────────────────────────────────────
    private Long          id;
    private Long          stepInstanceId;
    private Long          assignedUserId;
    private TaskStatus    status;
    private TaskRole      taskRole;        // ACTOR | ASSIGNER
    private LocalDateTime actedAt;
    private LocalDateTime dueAt;
    private LocalDateTime assignedAt;
    private String        remarks;
    private Long          delegatedToUserId;
    private Long          reassignedFromUserId;
    private Boolean       isAutoAssigned;

    // ── Workflow context ──────────────────────────────────────────────────────
    private String        workflowName;
    private Long          workflowId;
    private String        priority;
    private String        workflowInstanceStatus;

    // ── Step context ──────────────────────────────────────────────────────────
    private String        stepName;
    private Integer       stepOrder;

    /**
     * Resolved at response time based on taskRole:
     *   ACTOR    → from WorkflowStep.side
     *   ASSIGNER → from the assigner's own role side
     * Frontend uses this with entityType + resolvedStepAction to build the route.
     */
    private String        resolvedStepSide;

    /**
     * Resolved at response time based on taskRole:
     *   ACTOR    → from WorkflowStep.stepAction (FILL, REVIEW, ACKNOWLEDGE, etc.)
     *   ASSIGNER → always "ASSIGN" regardless of step's stepAction
     * Frontend uses this with entityType + resolvedStepSide to build the route.
     */
    private String        resolvedStepAction;

    /**
     * For ACTOR tasks: the role name that qualified this user for this task.
     * e.g. "VENDOR_VRM", "VENDOR_CISO", "VENDOR_RESPONDER", "REVIEWER", "AUDITOR_I".
     * Frontend uses this to dispatch to the correct sub-view on ASSIGN/FILL/REVIEW pages
     * without hardcoding step numbers or role name strings in UI components.
     * Null for ASSIGNER tasks and direct-user tasks.
     */
    private String        actorRoleName;

    /**
     * Nav table key — identifies which page renders this task.
     * Frontend looks this up in the nav table (already fetched at bootstrap)
     * to get the route. Replaces workflowRoutes.js entirely.
     * e.g. "vendor_assessment_fill", "vendor_assessment_assign", "issue_remediate"
     * Null for ASSIGNER tasks and SYSTEM steps.
     */
    private String        navKey;

    // ── Workflow instance context ──────────────────────────────────────────────
    private Long          workflowInstanceId;

    // ── Entity + artifact ─────────────────────────────────────────────────────
    private String        entityType;    // VENDOR | AUDIT | RISK | ...
    private Long          entityId;      // vendorId, auditId, etc.

    /**
     * Domain artifact ID resolved by WorkflowEntityResolverRegistry.
     * For VENDOR workflows: the VendorAssessment ID.
     * For AUDIT workflows: the AuditEngagement ID (future).
     * null if no resolver registered or artifact not yet created.
     *
     * Frontend routing: WORKFLOW_ROUTES[entityType][resolvedStepSide][resolvedStepAction](artifactId)
     */
    private Long          artifactId;
}