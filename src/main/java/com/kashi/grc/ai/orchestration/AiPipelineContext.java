package com.kashi.grc.ai.orchestration;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable state threaded through every step of a pipeline.
 *
 * ── WHY A SHARED BAG RATHER THAN TYPED STEP-TO-STEP PLUMBING ─────────────────
 * Steps are not a straight line. The critique step needs the draft AND the
 * original controls AND the org profile; the repair step needs the critique AND
 * the draft. Typing every one of those edges produces a combinatorial mess of
 * DTOs that changes every time a step is inserted. A bag keyed by name, with the
 * cross-cutting fields promoted to real properties, is the shape that survives
 * contact with pipeline evolution.
 *
 * ── BUDGET LIVES HERE ────────────────────────────────────────────────────────
 * tokensSpent accumulates across steps so the per-run cap in AiProperties.Budget
 * can be enforced by the runner between steps. A self-critique loop that fails
 * to converge is the exact scenario this exists for, and it will happen at least
 * once during development.
 */
@Getter
@Setter
public class AiPipelineContext {

    /** Groups every ai_interactions row from this run. */
    private final String correlationId = UUID.randomUUID().toString();

    private Long   tenantId;
    private Long   userId;
    private String entityType;
    private Long   entityId;

    /** Root interaction id — every step's row points its parent here. */
    private Long rootInteractionId;

    /** Chunk ids retrieved anywhere in the run, accumulated for provenance. */
    private final List<Long> retrievedChunkIds = new ArrayList<>();

    /** Interaction ids in execution order, for the "how was this made" panel. */
    private final List<Long> interactionIds = new ArrayList<>();

    private long tokensSpent = 0;
    private int  stepsExecuted = 0;

    /** Non-fatal problems worth surfacing: dropped references, trimmed context, low confidence. */
    private final List<String> warnings = new ArrayList<>();

    private final Map<String, Object> state = new LinkedHashMap<>();

    public AiPipelineContext put(String key, Object value) { state.put(key, value); return this; }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = state.get(key);
        return v == null ? null : (T) v;
    }

    public Object get(String key) { return state.get(key); }

    public boolean has(String key) { return state.containsKey(key); }

    public void addTokens(long n)              { this.tokensSpent += n; }
    public void addWarning(String w)           { this.warnings.add(w); }
    public void addChunks(List<Long> ids)      { if (ids != null) this.retrievedChunkIds.addAll(ids); }
    public void addInteraction(Long id) {
        if (id == null) return;
        this.interactionIds.add(id);
        if (this.rootInteractionId == null) this.rootInteractionId = id;
    }
}
