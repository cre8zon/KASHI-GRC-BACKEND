package com.kashi.grc.ai.domain;

import com.kashi.grc.ai.domain.AiEnums.InteractionStatus;
import com.kashi.grc.ai.domain.AiEnums.ProviderType;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AiInteraction — one row per model call. The audit trail IS the product.
 *
 * ── WHY THIS EXISTS ──────────────────────────────────────────────────────────
 * A GRC platform that cannot answer "why does this policy say what it says" is
 * not sellable to a regulated buyer. Auditors are already asking whether
 * artefacts were AI-generated and on what basis; ISO/IEC 42001 makes that a
 * documented requirement rather than a courtesy. Every single call to a model —
 * including the internal critique and repair steps a user never sees — lands
 * here before the result is allowed anywhere near a domain table.
 *
 * ── GLOBAL OR TENANT ─────────────────────────────────────────────────────────
 * Extends GlobalOrTenantEntity rather than TenantAwareEntity because platform
 * administrators generate against the GLOBAL library (tenant_id NULL) when they
 * author shipped templates. Those runs must be recorded too — they are how the
 * global corpus came to exist, and "who wrote the template we sold to 200
 * customers" is a question you will eventually be asked.
 *
 * ── PIPELINE SHAPE ───────────────────────────────────────────────────────────
 * One user action fans out into several rows. The first is the ROOT (parentId
 * null, correlationId generated); every step under it carries the same
 * correlationId and points its parentInteractionId at the root. Cost and latency
 * for the user-visible action are the SUM over a correlationId, never one row.
 *
 * ── WHAT WE DELIBERATELY DO NOT STORE ────────────────────────────────────────
 * The rendered prompt is stored POST-REDACTION. If PiiRedactor replaced a value,
 * the placeholder is what lands here — the row is an audit record, not a second
 * copy of the customer's personal data sitting outside its owning table.
 */
@Entity
@Table(name = "ai_interactions",
        indexes = {
                @Index(name = "idx_aii_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_aii_task",        columnList = "task_type"),
                @Index(name = "idx_aii_entity",      columnList = "entity_type,entity_id"),
                @Index(name = "idx_aii_correlation", columnList = "correlation_id"),
                @Index(name = "idx_aii_parent",      columnList = "parent_interaction_id"),
                @Index(name = "idx_aii_status",      columnList = "status"),
                @Index(name = "idx_aii_created",     columnList = "created_at")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AiInteraction extends GlobalOrTenantEntity {

    // ── What was asked ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 40)
    private TaskType taskType;

    /** Pipeline that produced this call, e.g. "policy-draft-v2". Null for one-shot calls. */
    @Column(name = "pipeline_name", length = 80)
    private String pipelineName;

    /** Ordinal of this step inside the pipeline — makes replay order recoverable. */
    @Column(name = "step_index")
    private Integer stepIndex;

    @Column(name = "step_name", length = 80)
    private String stepName;

    // ── Reproducibility: the four things you need to rerun this exactly ──────

    @Column(name = "prompt_template_key", length = 100)
    private String promptTemplateKey;

    @Column(name = "prompt_template_version")
    private Integer promptTemplateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    private ProviderType provider;

    /**
     * The CONFIGURED provider name — "grok", "gemini", "perplexity" — as opposed
     * to `provider` above, which records only the wire format.
     *
     * Your schema export made this gap obvious: ai_interactions.provider is an
     * enum of OPENAI / ANTHROPIC / BEDROCK / AZURE_OPENAI / LOCAL. Since Grok,
     * Gemini and Perplexity all speak the OpenAI dialect, every one of them
     * would be logged as OPENAI and the audit trail could not tell them apart.
     *
     * That is not a cosmetic loss. "Which sub-processor handled this data" is
     * precisely the question this table exists to answer in an enterprise
     * security review, and an answer of "OpenAI" when the call went to xAI is
     * worse than no answer at all.
     *
     * A plain varchar rather than an enum, deliberately: the set of providers is
     * configuration, so a new vendor must not require a schema migration.
     */
    @Column(name = "provider_key", length = 40)
    private String providerKey;

    @Column(name = "model", length = 120)
    private String model;

    @Column(name = "temperature")
    private Double temperature;

    // ── Inputs ────────────────────────────────────────────────────────────────

    /**
     * The rendered prompt actually sent, after redaction. LONGTEXT because a
     * grounded policy prompt with eight retrieved chunks runs to tens of KB.
     */
    @Column(name = "rendered_prompt", columnDefinition = "LONGTEXT")
    private String renderedPrompt;

    /**
     * JSON of the structured variables fed to the template — control IDs, org
     * profile fields, framework refs. Cheaper to query than parsing the prompt
     * back out, and it is what an auditor actually wants to see.
     */
    @Column(name = "input_variables", columnDefinition = "LONGTEXT")
    private String inputVariables;

    /**
     * Comma-separated ai_document_chunks IDs that grounded this call. THE
     * PROVENANCE FIELD. When a customer disputes a generated clause, this is
     * how you show which source produced it.
     */
    @Column(name = "retrieved_chunk_ids", columnDefinition = "TEXT")
    private String retrievedChunkIds;

    /**
     * SHA-256 of (templateKey|templateVersion|model|inputVariables). Lets you
     * detect and serve a cached identical generation instead of re-billing, and
     * lets the eval harness spot when a "regression" is really just a re-run.
     */
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    // ── Output ────────────────────────────────────────────────────────────────

    @Column(name = "raw_response", columnDefinition = "LONGTEXT")
    private String rawResponse;

    @Column(name = "finish_reason", length = 40)
    private String finishReason;

    // ── Accounting ────────────────────────────────────────────────────────────

    @Column(name = "prompt_tokens")     private Integer promptTokens;
    @Column(name = "completion_tokens") private Integer completionTokens;
    @Column(name = "total_tokens")      private Integer totalTokens;
    @Column(name = "cost_micros")       private Long    costMicros;
    @Column(name = "latency_ms")        private Long    latencyMs;
    @Column(name = "retry_count")       private Integer retryCount;

    // ── Outcome ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private InteractionStatus status = InteractionStatus.SUCCESS;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Guardrails that fired, comma-separated: "PII_REDACTED,INJECTION_SUSPECTED". */
    @Column(name = "guardrails_triggered", length = 500)
    private String guardrailsTriggered;

    // ── Linkage ───────────────────────────────────────────────────────────────

    /** Domain entity this call produced or modified, e.g. "AuditPolicy". Zero-FK by convention. */
    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    /** Groups every step of one user-visible action. */
    @Column(name = "correlation_id", length = 40)
    private String correlationId;

    @Column(name = "parent_interaction_id")
    private Long parentInteractionId;

    @Column(name = "triggered_by_user_id")
    private Long triggeredByUserId;

    /** True when the row was produced by the eval harness, so it can be excluded from billing reports. */
    @Column(name = "is_eval_run", nullable = false)
    @Builder.Default
    private Boolean evalRun = false;
}