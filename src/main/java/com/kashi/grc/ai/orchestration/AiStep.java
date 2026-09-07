package com.kashi.grc.ai.orchestration;

/**
 * One stage of a pipeline.
 *
 * A step may call a model, or may not. Assembling context, validating references
 * against the database and rendering JSON to HTML are all steps, and treating
 * them uniformly is what makes the whole run appear as one traceable sequence
 * rather than a model call surrounded by invisible glue.
 */
public interface AiStep {

    /** Appears in ai_interactions.step_name — keep it short and stable. */
    String name();

    /** Read from and write to the shared context. */
    void execute(AiPipelineContext ctx);

    /**
     * Skip this step for this run. Lets one pipeline serve related cases:
     * a retrieval step that skips when the corpus is empty, a critique step that
     * skips on the cheap tier.
     */
    default boolean shouldRun(AiPipelineContext ctx) { return true; }

    /**
     * When false, a thrown exception is logged and the pipeline continues.
     * Enrichment steps are optional; the generation step is not.
     */
    default boolean isCritical() { return true; }
}
