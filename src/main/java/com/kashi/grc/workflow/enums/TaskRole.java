package com.kashi.grc.workflow.enums;

/**
 * Distinguishes why a task was created for a user on a step.
 *
 * The same step can produce TWO kinds of tasks simultaneously:
 *
 *   ACTOR    → User does the actual work.
 *              stepAction from WorkflowStep determines the UI (FILL, REVIEW, etc.)
 *              stepSide from WorkflowStep determines which side's UI to use.
 *              Route = WORKFLOW_ROUTES[entityType][stepSide][stepAction]
 *
 *   ASSIGNER → User drives the assignment — picks who the ACTOR will be.
 *              Always routes to the ASSIGN UI regardless of stepAction.
 *              stepSide = the assigner's own role side (not the step's side).
 *              Route = WORKFLOW_ROUTES[entityType][assignerSide]["ASSIGN"]
 *
 * Example — Step "VRM Acknowledges Assessment" (side=VENDOR, stepAction=ACKNOWLEDGE):
 *
 *   Task A: taskRole=ASSIGNER, assignedTo=ORG_VRM_MANAGER
 *     → stepSide resolved as ORGANIZATION (assigner's side)
 *     → stepAction resolved as ASSIGN
 *     → route: WORKFLOW_ROUTES["VENDOR"]["ORGANIZATION"]["ASSIGN"]
 *     → /assessments/42/assign?taskId=...
 *
 *   Task B: taskRole=ACTOR, assignedTo=VENDOR_VRM (created after delegation)
 *     → stepSide = VENDOR (from WorkflowStep)
 *     → stepAction = ACKNOWLEDGE (from WorkflowStep)
 *     → route: WORKFLOW_ROUTES["VENDOR"]["VENDOR"]["ACKNOWLEDGE"]
 *     → /vendor/assessments/42?taskId=...
 */
public enum TaskRole {
    ACTOR,
    ASSIGNER
}