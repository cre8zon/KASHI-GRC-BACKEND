package com.kashi.grc.workflow.automation;

/**
 * Contract for an automated workflow step action.
 *
 * REGISTRATION:
 *   Implement this interface and annotate with @Component.
 *   AutomatedActionRegistry picks it up automatically on startup.
 *   The key returned by actionKey() is the string stored in
 *   workflow_steps.automated_action (e.g. "EXECUTE_ASSESSMENT").
 *
 * ADDING A NEW WORKFLOW ACTION:
 *   1. Create a class that implements AutomatedActionHandler
 *   2. Annotate with @Component
 *   3. Return a unique key from actionKey()
 *   4. Implement execute() with the business logic
 *   5. Set automated_action = <your key> on the SYSTEM step in the DB
 *      or via the workflow blueprint admin UI
 *
 * CONTRACT:
 *   execute() must NOT throw for recoverable errors — log and return false.
 *   Throw only for fatal configuration errors (e.g. missing template mapping).
 *   WorkflowEngineService will auto-approve the step on success (return true)
 *   or leave it IN_PROGRESS for manual intervention on failure (return false).
 *
 * EXAMPLE ACTIONS:
 *   "EXECUTE_ASSESSMENT"    → snapshot assessment template, create cycle
 *   "SEND_ONBOARDING_EMAIL" → send welcome email to vendor primary contact
 *   "CALCULATE_RISK"        → recalculate vendor risk score
 *   "GENERATE_AUDIT_REPORT" → create a PDF audit report
 *   "NOTIFY_STAKEHOLDERS"   → push notification to org stakeholders
 */
public interface AutomatedActionHandler {

    /**
     * Unique key stored in workflow_steps.automated_action.
     * Must be uppercase, underscore-separated. e.g. "EXECUTE_ASSESSMENT".
     */
    String actionKey();

    /**
     * Execute the automated action.
     *
     * @param ctx  full context: workflowInstance, step, stepInstance, tenantId, initiatedBy
     * @return true if the action succeeded and the step should auto-approve+advance;
     *         false if the action failed and the step should stay IN_PROGRESS for manual review.
     */
    boolean execute(AutomatedActionContext ctx);
}