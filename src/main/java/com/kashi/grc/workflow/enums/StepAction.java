package com.kashi.grc.workflow.enums;

/**
 * Declares what kind of work the ACTOR does on a workflow step.
 *
 * This drives frontend routing — the TaskInbox reads stepAction from the task
 * response and uses it (together with entityType + stepSide) to navigate to
 * the correct page. No step-name matching, no hardcoded URLs in the engine.
 *
 * Adding a new module (Audit, Risk, Policy) = add entries to the frontend
 * WORKFLOW_ROUTES config. This enum never needs to change.
 *
 * ASSIGN      → Actor assigns/delegates this step to someone else.
 *               e.g. VRM picks which CISO handles this vendor.
 *
 * FILL        → Actor fills in content: questionnaire answers, evidence, forms.
 *               e.g. Responder answers questions, contributor uploads evidence.
 *
 * REVIEW      → Actor reviews submitted content and approves/rejects/comments.
 *               e.g. Org CISO reviews the vendor's completed assessment.
 *
 * APPROVE     → Actor makes a final yes/no decision, no content editing.
 *               e.g. C-suite signs off on a risk acceptance.
 *
 * ACKNOWLEDGE → Actor acknowledges receipt or awareness of something.
 *               e.g. VRM confirms the assessment has been received.
 *
 * EVALUATE    → Actor evaluates evidence against criteria (audit style).
 *               e.g. Auditor scores controls against a framework.
 *
 * GENERATE    → Actor triggers generation of an artifact (report, certificate).
 *               e.g. Org CISO generates the final risk report.
 *
 * CUSTOM      → Step has a unique action that doesn't fit any above.
 *               Blueprint admin sets customRoute on the step explicitly.
 */
public enum StepAction {
    ASSIGN,
    FILL,
    REVIEW,
    APPROVE,
    ACKNOWLEDGE,
    EVALUATE,
    GENERATE,
    CUSTOM
}