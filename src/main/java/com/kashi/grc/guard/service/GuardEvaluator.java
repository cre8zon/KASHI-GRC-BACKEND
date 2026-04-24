package com.kashi.grc.guard.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.dto.ActionItemRequest;
import com.kashi.grc.actionitem.repository.ActionItemBlueprintRepository;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.actionitem.dto.ActionItemResponse;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.guard.domain.GuardRule;
import com.kashi.grc.guard.event.ModuleSubmitEvent.QuestionContext;
import com.kashi.grc.guard.repository.GuardRuleRepository;
import com.kashi.grc.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * KashiGuard — Generic Answer Trigger Evaluator.
 *
 * FULLY MODULE-AGNOSTIC: takes QuestionContext (a plain record) — no imports
 * from assessment, audit, policy, or any other module.
 *
 * CALLED FROM:
 *   1. Single answer submit → module controller calls evaluate() directly
 *   2. Bulk submit         → GuardEvaluationListener handles ModuleSubmitEvent
 *
 * ── TAG-BASED MATCHING ────────────────────────────────────────────────────────
 * Rules are matched by questionTagSnapshot, not by question ID.
 * If the question instance has no tag (null/blank), evaluation is skipped
 * entirely — no DB query, no action items, silent pass-through.
 *
 * ── SCALABILITY ───────────────────────────────────────────────────────────────
 * One rule with questionTag='ENCRYPTION' fires for every question carrying
 * that tag, across every module, every template, every assessment.
 * Adding new questions or modules = zero new guard rules needed.
 *
 * Match  → creates action item (idempotent) + sends notification to assignee
 * NoMatch→ auto-resolves existing open action item for this rule + instance
 *
 * ── SUBMISSION PHILOSOPHY ────────────────────────────────────────────────────
 * Assessment submission is NEVER BLOCKED by unanswered or flagged questions.
 * Guard evaluation runs async after commit — the HTTP response returns immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardEvaluator {

    private final GuardRuleRepository           guardRuleRepository;
    private final ActionItemRepository          actionItemRepository;
    private final ActionItemBlueprintRepository blueprintRepository;
    private final ActionItemService             actionItemService;
    private final NotificationService           notificationService;

    private static final Long SYSTEM_USER_ID = 0L;

    /**
     * Evaluate a single question against all matching guard rules.
     *
     * If questionTagSnapshot is null or blank, returns immediately — untagged
     * questions are never evaluated. No DB query, no side effects.
     */
    @Async
    @Transactional
    public void evaluate(QuestionContext ctx, Long tenantId) {

        // Untagged questions are silently skipped — no error, no log spam
        if (ctx.questionTagSnapshot() == null || ctx.questionTagSnapshot().isBlank()) {
            return;
        }

        // Criteria-based lookup — tag + tenant scope (global + tenant-specific)
        List<GuardRule> rules = guardRuleRepository
                .findActiveRulesForTag(ctx.questionTagSnapshot(), tenantId);

        if (rules.isEmpty()) {
            log.debug("[KASHI-GUARD] No rules for tag='{}' instanceId={}",
                    ctx.questionTagSnapshot(), ctx.questionInstanceId());
            return;
        }

        log.debug("[KASHI-GUARD] {} rule(s) for tag='{}' instanceId={}",
                rules.size(), ctx.questionTagSnapshot(), ctx.questionInstanceId());

        for (GuardRule rule : rules) {
            try {
                if (evaluateCondition(rule, ctx)) handleMatch(rule, ctx, tenantId);
                else                               handleNoMatch(rule, ctx.questionInstanceId(), tenantId);
            } catch (Exception e) {
                log.warn("[KASHI-GUARD] Rule {} failed: {}", rule.getId(), e.getMessage());
            }
        }
    }

    /**
     * Convenience overload for single-answer submit from module controllers.
     * The controller passes questionTagSnapshot directly from the question instance.
     */
    @Async
    @Transactional
    public void evaluate(String questionTagSnapshot,
                         Long questionInstanceId,
                         String responseText,
                         boolean fileUploaded,
                         Double score,
                         String navContext,
                         Long tenantId) {
        evaluate(new QuestionContext(questionTagSnapshot, questionInstanceId,
                responseText, fileUploaded, score, navContext), tenantId);
    }

    // ── Condition evaluation ──────────────────────────────────────────────────

    private boolean evaluateCondition(GuardRule rule, QuestionContext ctx) {
        return switch (rule.getConditionType()) {
            case OPTION_SELECTED     -> ctx.responseText() != null
                    && ctx.responseText().equalsIgnoreCase(rule.getConditionValue());

            case OPTION_NOT_SELECTED -> ctx.responseText() == null
                    || !ctx.responseText().equalsIgnoreCase(rule.getConditionValue());

            case TEXT_CONTAINS       -> ctx.responseText() != null
                    && !ctx.responseText().isBlank()
                    && rule.getConditionValue() != null
                    && ctx.responseText().toLowerCase()
                    .contains(rule.getConditionValue().toLowerCase());

            case TEXT_EMPTY          -> ctx.responseText() == null
                    || ctx.responseText().isBlank();

            case FILE_NOT_UPLOADED   -> !ctx.fileUploaded();

            case SCORE_BELOW         -> {
                try { yield ctx.score() != null
                        && ctx.score() < Double.parseDouble(rule.getConditionValue()); }
                catch (NumberFormatException e) { yield false; }
            }

            case SCORE_ABOVE         -> {
                try { yield ctx.score() != null
                        && ctx.score() > Double.parseDouble(rule.getConditionValue()); }
                catch (NumberFormatException e) { yield false; }
            }

            case ANY_ANSWER          -> true;

            case ANSWER_MISSING      ->
                // Completely skipped: no text, no file, no score
                    ctx.responseText() == null && !ctx.fileUploaded() && ctx.score() == null;

            case SCORE_NOT_SET       ->
                // Scored question but score was left null
                    ctx.score() == null;
        };
    }

    // ── Match → create action item + notify ──────────────────────────────────

    private void handleMatch(GuardRule rule, QuestionContext ctx, Long tenantId) {
        Long qi = ctx.questionInstanceId();

        // Idempotent — skip if open item already exists for this rule + instance
        boolean alreadyOpen = !actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.forEntity(
                                ActionItem.EntityType.QUESTION_RESPONSE, qi))
                        .and(ActionItemSpecification.forSource(
                                ActionItem.SourceType.SYSTEM, rule.getId()))
                        .and(ActionItemSpecification.open())
        ).isEmpty();

        if (alreadyOpen) {
            log.debug("[KASHI-GUARD] Rule {} already open for qi={}", rule.getId(), qi);
            return;
        }

        var blueprint = blueprintRepository.findByBlueprintCode(rule.getBlueprintCode())
                .orElse(null);
        if (blueprint == null) {
            log.warn("[KASHI-GUARD] Blueprint '{}' not found for rule {}",
                    rule.getBlueprintCode(), rule.getId());
            return;
        }

        ActionItemRequest req = new ActionItemRequest();
        req.setBlueprintId(blueprint.getId());
        req.setSourceType(ActionItem.SourceType.SYSTEM);
        req.setSourceId(rule.getId());
        req.setEntityType(ActionItem.EntityType.QUESTION_RESPONSE);
        req.setEntityId(qi);
        req.setAssignedGroupRole(rule.getAssignedRole() != null
                ? rule.getAssignedRole() : blueprint.getResolutionRole());
        req.setResolutionRole(blueprint.getResolutionRole());
        req.setTitle(blueprint.getTitleTemplate());
        req.setDescription(blueprint.getDescriptionTemplate());
        req.setPriority(rule.getPriorityOverride() != null
                ? ActionItem.Priority.valueOf(rule.getPriorityOverride())
                : blueprint.getDefaultPriority());
        req.setNavContext(ctx.navContext());

        ActionItemResponse created = actionItemService.create(req, SYSTEM_USER_ID, tenantId);
        log.info("[KASHI-GUARD] Rule {} MATCH → action item {} | tag='{}' qi={} blueprint={} condition={}",
                rule.getId(), created != null ? created.getId() : "?",
                ctx.questionTagSnapshot(), qi,
                rule.getBlueprintCode(), rule.getConditionType());

        if (created != null && created.getAssignedTo() != null) {
            notificationService.send(
                    created.getAssignedTo(),
                    "ACTION_ITEM_CREATED",
                    blueprint.getTitleTemplate(),
                    "QUESTION_RESPONSE",
                    qi
            );
        }
    }

    // ── No match → auto-resolve existing open item ───────────────────────────

    private void handleNoMatch(GuardRule rule, Long questionInstanceId, Long tenantId) {
        actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.forEntity(
                                ActionItem.EntityType.QUESTION_RESPONSE, questionInstanceId))
                        .and(ActionItemSpecification.forSource(
                                ActionItem.SourceType.SYSTEM, rule.getId()))
                        .and(ActionItemSpecification.open())
        ).forEach(item -> {
            item.setStatus(ActionItem.Status.RESOLVED);
            item.setResolvedBy(SYSTEM_USER_ID);
            item.setResolutionNote("Auto-resolved: answer no longer triggers rule " + rule.getId());
            actionItemRepository.save(item);
            log.info("[KASHI-GUARD] Rule {} NO MATCH → resolved item={} qi={}",
                    rule.getId(), item.getId(), questionInstanceId);
        });
    }
}