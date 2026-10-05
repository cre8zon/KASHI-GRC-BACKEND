package com.kashi.grc.workflow.event;

/**
 * Fired by WorkflowEngineService.resetTask() once an admin reset has finished.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────────
 *
 * resetTask puts the TASK back to IN_PROGRESS, re-arms its section gates and
 * clears its task_section_items. That is everything the workflow engine owns,
 * and for most workflows it is everything there is.
 *
 * It is not everything for a module that keeps a lock of its own. The vendor
 * assessment keeps three, none of which the engine can see:
 *
 *   assessment_section_instances.submitted_at           the responder's lock
 *   assessment_section_instances.reviewer_submitted_at  the reviewer's lock
 *   contributor_section_submissions  /                  the contributor's and
 *   reviewer_assistant_section_submissions              assistant's own locks
 *
 * So an admin who reset a "Responders Fill Questionnaires" task got a task the
 * responder could see and sections they still could not touch: the Submit
 * control stays hidden because the section reads as submitted, and the re-armed
 * gate can never close because nothing can fire it. The task was reopened and
 * the work was not.
 *
 * The existing answer to that was a second endpoint,
 * POST /v1/assessments/{id}/reset-reviewer-sections, whose own javadoc says
 * "Pair with workflowsApi.instances.resetTask() for full task reopen" — a
 * pairing nothing enforced and no caller performed. WorkflowTimeline, the only
 * place with a reset button, calls resetTask alone. And there was never a
 * vendor-side equivalent at all, which is the half the responder hit.
 *
 * ── WHY AN EVENT AND NOT A CALL ───────────────────────────────────────────────
 *
 * The workflow engine must not know what a questionnaire section is. This is
 * the same contract SectionItemsNeededEvent already uses in the other
 * direction: the engine announces that something happened, and each module
 * cleans up the state it owns. Audit, issue, policy and risk have no listener
 * and are unaffected — publishing an event nobody consumes changes nothing.
 *
 * ── LISTENER CONTRACT ─────────────────────────────────────────────────────────
 *
 * A listener MUST scope its work to assignedUserId. A reset is for ONE task,
 * which belongs to ONE person; clearing another responder's lock because their
 * colleague's task was reset would be the multi-actor bug in a new place.
 *
 * stepAction is the snapshotted WorkflowStep.stepAction (FILL, EVALUATE,
 * ASSIGN, REVIEW …) and is how a listener decides WHICH of its locks this reset
 * refers to — a reset on a FILL task must not clear a reviewer's submission.
 *
 * Listeners run inside resetTask's transaction. A listener that throws rolls
 * the reset back, so each one is responsible for catching its own failures:
 * being unable to clear a module lock is not a reason to leave the task
 * un-reset.
 *
 * @param taskInstanceId     the task that was reset
 * @param stepInstanceId     its step instance
 * @param workflowInstanceId the workflow instance — how a module finds its entity
 * @param tenantId           tenant scope; never trust a lookup without it
 * @param entityType         WorkflowInstance.entityType, e.g. "VENDOR_ASSESSMENT"
 * @param entityId           WorkflowInstance.entityId
 * @param assignedUserId     who the task belongs to — SCOPE ALL WORK TO THIS
 * @param stepAction         snapshotted step action: FILL, EVALUATE, ASSIGN, REVIEW
 * @param previousStatus     what the task was before the reset
 * @param performedBy        the admin who reset it
 * @param rollbackDownstream whether downstream steps were rolled back too
 */
public record TaskResetEvent(
        Long   taskInstanceId,
        Long   stepInstanceId,
        Long   workflowInstanceId,
        Long   tenantId,
        String entityType,
        Long   entityId,
        Long   assignedUserId,
        String stepAction,
        String previousStatus,
        Long   performedBy,
        boolean rollbackDownstream
) {}