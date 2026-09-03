package com.kashi.grc.ai.orchestration;

import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.guardrail.GuardrailException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A named sequence of steps, executed with budget and step-count enforcement.
 *
 * ── THIS IS THE DIFFERENCE BETWEEN "AI FEATURE" AND "AI PRODUCT" ─────────────
 * A single prompt-and-return is a demo. What mature tools ship is a pipeline:
 * gather the grounding, generate, have the model criticise its own output
 * against explicit criteria, repair what the critique found, validate every
 * reference against the database, then render. The user sees one button. Six to
 * ten model calls happen behind it, and the quality difference between one call
 * and that sequence is not subtle — it is the difference between output a
 * compliance lead edits heavily and output they mostly accept.
 *
 * ── WHY THE CAPS ARE NOT NEGOTIABLE ──────────────────────────────────────────
 * Two failure modes, both of which will occur:
 *   - a critique step that never declares the draft good enough, looping until
 *     someone notices the bill
 *   - a repair step whose repair fails validation, prompting another repair
 * maxPipelineSteps and tokensPerPipelineRun turn both from an incident into a
 * log line and a partial result.
 */
@Slf4j
@Getter
public class AiPipeline {

    private final String name;
    private final List<AiStep> steps = new ArrayList<>();
    private final AiProperties props;

    public AiPipeline(String name, AiProperties props) {
        this.name  = name;
        this.props = props;
    }

    public AiPipeline add(AiStep step) { steps.add(step); return this; }

    public AiPipelineContext run(AiPipelineContext ctx) {
        long started = System.currentTimeMillis();
        log.info("[AI-PIPELINE] {} starting | correlationId={} tenantId={}",
                name, ctx.getCorrelationId(), ctx.getTenantId());

        for (AiStep step : steps) {

            if (ctx.getStepsExecuted() >= props.getBudget().getMaxPipelineSteps()) {
                log.error("[AI-PIPELINE] {} hit the step cap ({}) — aborting", name, props.getBudget().getMaxPipelineSteps());
                ctx.addWarning("Generation stopped at the step limit; the result may be incomplete");
                break;
            }
            if (ctx.getTokensSpent() > props.getBudget().getTokensPerPipelineRun()) {
                log.error("[AI-PIPELINE] {} hit the per-run token cap ({}) — aborting",
                        name, props.getBudget().getTokensPerPipelineRun());
                ctx.addWarning("Generation stopped at the token limit; the result may be incomplete");
                break;
            }

            if (!step.shouldRun(ctx)) {
                log.debug("[AI-PIPELINE] {} | step '{}' skipped", name, step.name());
                continue;
            }

            long stepStart = System.currentTimeMillis();
            try {
                step.execute(ctx);
                ctx.setStepsExecuted(ctx.getStepsExecuted() + 1);
                log.debug("[AI-PIPELINE] {} | step '{}' ok in {}ms", name, step.name(), System.currentTimeMillis() - stepStart);

            } catch (GuardrailException e) {
                // A guardrail refusal is always terminal. Continuing past a
                // budget stop or an injection block would defeat the guardrail.
                log.warn("[AI-PIPELINE] {} | step '{}' blocked: {}", name, step.name(), e.getMessage());
                throw e;

            } catch (RuntimeException e) {
                if (step.isCritical()) {
                    log.error("[AI-PIPELINE] {} | critical step '{}' failed", name, step.name(), e);
                    throw new com.kashi.grc.common.exception.BusinessException(
                            "AI_PIPELINE_FAILED",
                            "Generation failed at step '" + step.name() + "': " + e.getMessage(),
                            HttpStatus.UNPROCESSABLE_ENTITY);
                }
                log.warn("[AI-PIPELINE] {} | optional step '{}' failed, continuing: {}", name, step.name(), e.getMessage());
                ctx.addWarning("Optional step '" + step.name() + "' did not complete");
            }
        }

        log.info("[AI-PIPELINE] {} finished | steps={} tokens={} duration={}ms warnings={}",
                name, ctx.getStepsExecuted(), ctx.getTokensSpent(),
                System.currentTimeMillis() - started, ctx.getWarnings().size());
        return ctx;
    }
}
