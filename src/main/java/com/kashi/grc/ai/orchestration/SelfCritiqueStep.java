package com.kashi.grc.ai.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kashi.grc.ai.chat.AiChatService;
import com.kashi.grc.ai.chat.AiChatService.AiCall;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the model to grade its own output against explicit criteria, then feeds
 * the critique back for one revision.
 *
 * ── DOES SELF-CRITIQUE ACTUALLY WORK? ────────────────────────────────────────
 * Partly, and the boundary matters. A model is poor at noticing it has invented
 * a fact — the invention and the verification share the same blind spot. It is
 * genuinely good at checking output against CONCRETE, ENUMERABLE criteria:
 * is every required section present, does the text ever contradict the supplied
 * org profile, are there placeholder brackets left in, does the tone match, is
 * anything asserted that the grounding did not support.
 *
 * So the criteria passed in must be specific and checkable. "Is this a good
 * policy?" produces flattery. "List every section named in the outline that is
 * missing from the draft" produces a usable list. The prompt template shipped
 * with this module is written that way, and any new criteria should be too.
 *
 * ── WHY ONE ROUND, NOT LOOP-UNTIL-CLEAN ──────────────────────────────────────
 * The gain from round one is large and from round three is noise, while the cost
 * is linear and the risk of a non-converging loop is real. One round, then move
 * on, and let a human do what humans are better at.
 */
@Slf4j
@RequiredArgsConstructor
public class SelfCritiqueStep implements AiStep {

    private final AiChatService chatService;
    private final String critiqueTemplateKey;
    private final String reviseTemplateKey;
    /** Context key holding the draft to critique, and where the revision is written back. */
    private final String draftKey;
    private final List<String> criteria;

    @Override public String name() { return "self-critique"; }

    /** Optional: a failed critique should never lose a usable draft. */
    @Override public boolean isCritical() { return false; }

    @Override
    public boolean shouldRun(AiPipelineContext ctx) {
        Object draft = ctx.get(draftKey);
        return draft != null && !String.valueOf(draft).isBlank();
    }

    @Override
    public void execute(AiPipelineContext ctx) {
        String draft = String.valueOf(ctx.get(draftKey));

        // 1 ── critique. Cheap model; this is a checklist task, not a creative one.
        AiChatService.AiResult critique = chatService.completeJson(
                AiCall.of(critiqueTemplateKey, TaskType.SELF_CRITIQUE)
                        .tenant(ctx.getTenantId()).user(ctx.getUserId())
                        .correlation(ctx.getCorrelationId()).parent(ctx.getRootInteractionId())
                        .pipeline("self-critique").step(ctx.getStepsExecuted(), name())
                        .entity(ctx.getEntityType(), ctx.getEntityId())
                        .var("draft", draft)
                        .var("criteria", criteria));

        ctx.addInteraction(critique.interactionId());
        ctx.addTokens(critique.totalTokens());

        JsonNode json = critique.json();
        if (json == null) { log.debug("[AI-CRITIQUE] no parseable critique, leaving draft untouched"); return; }

        List<String> issues = new ArrayList<>();
        for (JsonNode issue : json.path("issues")) {
            String text = issue.isTextual() ? issue.asText() : issue.path("issue").asText(null);
            if (text != null && !text.isBlank()) issues.add(text);
        }

        ctx.put(draftKey + ".critique", issues);
        ctx.put(draftKey + ".critiqueScore", json.path("score").asDouble(-1));

        if (issues.isEmpty()) { log.debug("[AI-CRITIQUE] draft passed with no issues"); return; }
        log.info("[AI-CRITIQUE] {} issue(s) found, revising", issues.size());

        // 2 ── revise, naming the issues explicitly
        AiChatService.AiResult revised = chatService.complete(
                AiCall.of(reviseTemplateKey, TaskType.SELF_CRITIQUE)
                        .tenant(ctx.getTenantId()).user(ctx.getUserId())
                        .correlation(ctx.getCorrelationId()).parent(ctx.getRootInteractionId())
                        .pipeline("self-critique").step(ctx.getStepsExecuted() + 1, "revise")
                        .entity(ctx.getEntityType(), ctx.getEntityId())
                        .var("draft", draft)
                        .var("issues", issues));

        ctx.addInteraction(revised.interactionId());
        ctx.addTokens(revised.totalTokens());

        if (revised.content() != null && !revised.content().isBlank()) {
            ctx.put(draftKey, revised.content());
            ctx.put(draftKey + ".revised", true);
        }
    }
}
