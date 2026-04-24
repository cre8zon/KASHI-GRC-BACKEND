package com.kashi.grc.workflow.automation;

import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.domain.WorkflowStep;
import com.kashi.grc.workflow.domain.StepInstance;
import lombok.Builder;
import lombok.Getter;

/**
 * Context passed to every AutomatedActionHandler when a SYSTEM step fires.
 *
 * Contains everything the handler needs to do its job and advance the step.
 * Handlers must NOT reach outside this context to load workflow data —
 * all required state is provided here by WorkflowEngineService.
 */
@Getter
@Builder
public class AutomatedActionContext {

    /** The running workflow instance. */
    private final WorkflowInstance workflowInstance;

    /** The blueprint step that declared this automatedAction. */
    private final WorkflowStep     step;

    /** The StepInstance just created for this SYSTEM step. */
    private final StepInstance     stepInstance;

    /** Tenant the instance belongs to. */
    private final Long             tenantId;

    /** User who initiated the workflow (or triggered this step). */
    private final Long             initiatedBy;
}