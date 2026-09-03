package com.kashi.grc.ai.domain;

import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AiPromptTemplate — a versioned, database-resident prompt.
 *
 * ── WHY NOT JUST HARD-CODE THE STRINGS ───────────────────────────────────────
 * Prompts are the highest-churn artefact in an AI product; you will change them
 * weekly. Three consequences follow, and all three are why this is a table:
 *
 *   1. A prompt change is a behaviour change to a compliance artefact. It has to
 *      be versioned and attributable, exactly like a policy revision.
 *   2. ai_interactions records templateKey + templateVersion. If prompts live in
 *      a Java string, that pair points at nothing after the next deploy and the
 *      audit trail is decorative.
 *   3. Iterating on a prompt should not require a release. A compliance lead who
 *      is not a Java developer is usually the right person to tune this text.
 *
 * ── VERSIONING IS APPEND-ONLY ────────────────────────────────────────────────
 * Editing NEVER mutates a row. A change writes a new row at version+1 and clears
 * `active` on the old one — the same shape as your AuditPolicy version chain.
 * Old versions stay queryable forever so a six-month-old generation remains
 * explainable.
 *
 * ── GLOBAL VS TENANT ─────────────────────────────────────────────────────────
 * tenant_id NULL = platform-shipped prompt available to everyone. A tenant row
 * with the same key SHADOWS the global one, which is how an enterprise customer
 * gets house tone-of-voice without forking the product. PromptRegistry resolves
 * tenant-first, global-fallback.
 *
 * ── SEEDING ──────────────────────────────────────────────────────────────────
 * Ships as JSON under classpath:ai/prompts/. PromptRegistry loads those on first
 * miss and persists them as version 1 global rows, so a fresh database is
 * immediately functional and the files remain the reviewable source of truth
 * in git.
 */
@Entity
@Table(name = "ai_prompt_templates",
        indexes = {
                @Index(name = "idx_apt_key",    columnList = "template_key"),
                @Index(name = "idx_apt_tenant", columnList = "tenant_id"),
                @Index(name = "idx_apt_task",   columnList = "task_type"),
                @Index(name = "idx_apt_active", columnList = "is_active")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uk_apt_key_ver_tenant",
                columnNames = {"template_key", "version", "tenant_id"})
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AiPromptTemplate extends GlobalOrTenantEntity {

    /** Stable lookup key, e.g. "policy.draft.system". Never changes across versions. */
    @Column(name = "template_key", nullable = false, length = 100)
    private String templateKey;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 40)
    private TaskType taskType;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ── The prompt itself ─────────────────────────────────────────────────────

    /**
     * System message. Role, constraints, refusal rules. Keep the invariants here
     * rather than in the user message — the user message carries data, the system
     * message carries rules, and mixing them is how injected content ends up
     * being read as instruction.
     */
    @Column(name = "system_prompt", columnDefinition = "LONGTEXT")
    private String systemPrompt;

    /** User message with {{placeholders}} rendered by PromptRenderer. */
    @Column(name = "user_prompt", columnDefinition = "LONGTEXT", nullable = false)
    private String userPrompt;

    /**
     * Comma-separated variable names the renderer REQUIRES. A missing one is a
     * hard failure, not an empty substitution — a prompt that silently renders
     * "generate a policy for  in the  industry" produces confident garbage that
     * looks fine until a customer reads it.
     */
    @Column(name = "required_variables", length = 1000)
    private String requiredVariables;

    // ── Output contract ───────────────────────────────────────────────────────

    /**
     * When set, the response must parse as JSON and satisfy this schema before
     * any caller sees it. Deliberately a minimal subset — required keys and
     * types — rather than full JSON Schema, so it needs no extra dependency.
     * See JsonSchemaGuard.
     */
    @Column(name = "response_schema", columnDefinition = "LONGTEXT")
    private String responseSchema;

    @Column(name = "expects_json", nullable = false)
    @Builder.Default
    private Boolean expectsJson = false;

    // ── Model hints (all optional; null falls back to AiProperties) ───────────

    @Column(name = "model_hint", length = 120)
    private String modelHint;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    /** Use the cheap model. Set on mechanical steps — critique, extraction, classification. */
    @Column(name = "prefer_fast_model", nullable = false)
    @Builder.Default
    private Boolean preferFastModel = false;

    // ── Retrieval behaviour ───────────────────────────────────────────────────

    /** Whether this task grounds itself in retrieved chunks at all. */
    @Column(name = "uses_retrieval", nullable = false)
    @Builder.Default
    private Boolean usesRetrieval = false;

    /** Restrict retrieval to these ChunkSourceType values, comma-separated. */
    @Column(name = "retrieval_source_types", length = 300)
    private String retrievalSourceTypes;

    @Column(name = "retrieval_top_k")
    private Integer retrievalTopK;

    // ── Provenance ────────────────────────────────────────────────────────────

    @Column(name = "created_by")
    private Long createdBy;

    /** Free-text note on why this version differs from the last. Shown in the admin diff. */
    @Column(name = "change_note", columnDefinition = "TEXT")
    private String changeNote;

    @Column(name = "previous_version_id")
    private Long previousVersionId;
}
