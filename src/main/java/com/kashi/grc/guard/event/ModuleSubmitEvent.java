package com.kashi.grc.guard.event;

import java.util.List;

/**
 * ModuleSubmitEvent — generic Spring event published by any module controller
 * when a "submission" action should trigger a full guard sweep.
 *
 * DESIGN CONTRACT:
 *   The publishing module translates its own domain objects into
 *   List<QuestionContext>. GuardEvaluator never imports any module class.
 *   navContext is a JSON string the module builds itself — it owns its routes.
 *
 * ── QUESTION TAG vs QUESTION ID ──────────────────────────────────────────────
 * QuestionContext carries questionTagSnapshot (the snapshotted tag from the
 * question instance), NOT a question ID. This decouples the guard system from
 * the question library entirely:
 *
 *   - GuardEvaluator never needs to know the question's ID
 *   - GuardRules match on tag strings — one rule covers all questions with that tag
 *   - questionInstanceId is still carried for action item creation + idempotency
 *     (the action item is linked to the specific instance, not the tag or template)
 *
 * USAGE (any module controller):
 *
 *   List<QuestionContext> contexts = questions.stream().map(qi -> {
 *       MyResponse r = responseMap.get(qi.getId());
 *       return new ModuleSubmitEvent.QuestionContext(
 *           qi.getQuestionTagSnapshot(),           // tag from the instance snapshot
 *           qi.getId(),                            // instance ID for action item
 *           r != null ? r.getResponseText() : null,
 *           r != null && r.hasFile(),
 *           r != null ? r.getScore() : null,
 *           buildNavContext(entityId, qi.getId())  // module-specific JSON
 *       );
 *   }).toList();
 *
 *   eventPublisher.publishEvent(new ModuleSubmitEvent(
 *       "VENDOR_ASSESSMENT", assessmentId, taskId, userId, tenantId, contexts
 *   ));
 *
 * ADDING A NEW MODULE:
 *   1. Build List<QuestionContext> from your domain objects (use questionTagSnapshot)
 *   2. Publish ModuleSubmitEvent — zero changes to GuardEvaluator or listener
 */
public record ModuleSubmitEvent(
        String               entityType,   // "VENDOR_ASSESSMENT" | "AUDIT" | "POLICY" | ...
        Long                 entityId,
        Long                 taskId,
        Long                 performedBy,
        Long                 tenantId,
        List<QuestionContext> questions
) {

    /**
     * Module-agnostic snapshot of one question + its current answer state.
     *
     * questionTagSnapshot — the tag that was snapshotted onto the question instance
     *   at assessment instantiation time. Null means the question was untagged;
     *   the guard system will skip it silently.
     *
     * questionInstanceId — the ID of AssessmentQuestionInstance (or equivalent).
     *   Used for action item entity linking and idempotency checks.
     *   The guard system creates action items scoped to this specific instance,
     *   so the same tag on 50 different instances = 50 separate, trackable items.
     *
     * Null responseText + fileUploaded=false + score=null = question never answered.
     * This is what the ANSWER_MISSING condition type checks for.
     */
    public record QuestionContext(
            String  questionTagSnapshot, // tag from instance — guard rule lookup key
            Long    questionInstanceId,  // instance ID — action item entity + idempotency
            String  responseText,        // null if never answered
            boolean fileUploaded,        // true if a file was attached
            Double  score,               // numeric score, null if not applicable
            String  navContext           // JSON: { assigneeRoute, reviewerRoute, questionInstanceId, ... }
    ) {
        /**
         * Convenience for a completely unanswered question.
         * ANSWER_MISSING condition fires for these.
         */
        public static QuestionContext unanswered(String questionTagSnapshot,
                                                 Long questionInstanceId,
                                                 String navContext) {
            return new QuestionContext(questionTagSnapshot, questionInstanceId,
                    null, false, null, navContext);
        }
    }
}