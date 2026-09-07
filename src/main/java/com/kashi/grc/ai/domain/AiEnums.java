package com.kashi.grc.ai.domain;

/**
 * Every enum the AI subsystem uses, in one file — mirrors the CommonControlDtos
 * convention of grouping small related types rather than scattering one-line files.
 */
public final class AiEnums {

    private AiEnums() {}

    /**
     * What the AI was asked to do. This is the single most important dimension
     * in ai_interactions: it drives cost reporting, the eval suite, the feedback
     * flywheel and per-task prompt lookup.
     *
     * ADD LIBERALLY. Your platform is metadata-driven, so most future AI surfaces
     * are a new task type plus a prompt template row — not new code.
     */
    public enum TaskType {
        // ── Policy (phase 1) ────────────────────────────────────────────────
        POLICY_DRAFT,            // full policy body from controls + org profile
        POLICY_SECTION_REWRITE,  // rewrite/expand/simplify a selected passage
        POLICY_CONTROL_MAPPING,  // suggest which controls this policy satisfies
        POLICY_GAP_ANALYSIS,     // what a framework expects that the policy lacks
        POLICY_CLAUSE_EXPLAIN,   // plain-English gloss for a reviewer
        POLICY_CHANGE_SUMMARY,   // diff two versions into a review note
        POLICY_METADATA_EXTRACT, // pull title/version/SLA out of an uploaded doc

        // ── Adjacent surfaces, wired later against the same platform ────────
        CONTROL_DESCRIPTION,
        RISK_NARRATIVE,
        EVIDENCE_SUMMARY,
        VENDOR_QUESTIONNAIRE_ANSWER,
        VENDOR_REPORT_SUMMARY,
        ASSESSMENT_ANSWER_SUGGEST,
        ISSUE_REMEDIATION_PLAN,

        // ── Content platform ────────────────────────────────────────
        // Its own block, not appended to the policy group, because the risk
        // profile differs: policy output goes to one customer's reviewer,
        // content output goes to the public internet under a named byline.
        CONTENT_OUTLINE,          // topic + persona + keyword    -> H2/H3 outline
        CONTENT_DRAFT_SECTION,    // outline + one heading        -> that section only
        CONTENT_REWRITE,          // selection + instruction      -> rewritten selection
        CONTENT_META,             // title + first 300 words      -> metaTitle + metaDescription
        CONTENT_TLDR,             // full post                    -> 3-5 takeaway bullets
        CONTENT_FAQ,              // full post                    -> 4-6 Q&A pairs
        CONTENT_INTERNAL_LINKS,   // draft + published slugs      -> suggested links (guarded)
        CONTENT_SOCIAL,           // published post               -> LinkedIn / X draft

        // ── Internal machinery ──────────────────────────────────────────────
        SELF_CRITIQUE,           // a pipeline step reviewing another step's output
        JSON_REPAIR,             // schema-invalid output sent back for correction
        EMBEDDING,               // vector generation (no chat model involved)
        EVAL_JUDGE               // LLM-as-judge scoring inside the eval harness
    }

    /** Which vendor served a call. Stored per interaction so a model swap is traceable. */
    public enum ProviderType {
        OPENAI,
        ANTHROPIC,
        BEDROCK,
        AZURE_OPENAI,
        LOCAL
    }

    /**
     * Terminal state of one model call or pipeline step.
     *
     * BLOCKED is deliberately distinct from FAILED: it means a guardrail refused
     * the call (budget exhausted, injection detected, prompt oversized). Those
     * are product events worth alerting on, not infrastructure errors.
     */
    public enum InteractionStatus {
        SUCCESS,
        FAILED,
        BLOCKED,
        TIMEOUT,
        INVALID_OUTPUT
    }

    /**
     * Where a retrievable chunk came from. Drives both the retrieval filter
     * (a policy draft should not be grounded in another tenant's evidence) and
     * the re-ingestion sweep when a source record changes.
     */
    public enum ChunkSourceType {
        POLICY,              // AuditPolicy.contentBody
        POLICY_TEMPLATE,     // global AuditPolicy shipped by the platform
        CONTROL,             // AuditControl / CommonControl requirement text
        FRAMEWORK_TEXT,      // public-domain standard text — see the copyright note
        EVIDENCE_DOCUMENT,   // uploaded PDF/DOCX in the evidence store
        VENDOR_DOCUMENT,     // vendor SOC 2 / questionnaire response
        ASSESSMENT_RESPONSE,
        UPLOADED_LEGACY_POLICY,
        KNOWLEDGE_NOTE       // free-text org knowledge captured by the customer
    }

    /**
     * What the human did with a suggestion. THIS IS THE FLYWHEEL.
     *
     * EDITED is the highest-signal value in the whole system: the human wanted
     * the suggestion but not as written, and the delta between originalValue and
     * finalValue is exactly the correction the model needs. Capture it even
     * though it costs a column, because it cannot be backfilled later.
     */
    public enum FeedbackDecision {
        ACCEPTED,
        ACCEPTED_WITH_EDIT,
        REJECTED,
        IGNORED,          // shown, never acted on, dismissed by navigating away
        FLAGGED_WRONG     // explicitly reported as incorrect/hallucinated
    }

    /** Lifecycle of an ingestion job (source text -> chunks -> vectors). */
    public enum IngestionStatus {
        PENDING,
        EXTRACTING,
        CHUNKING,
        EMBEDDING,
        INDEXING,
        COMPLETED,
        FAILED,
        SKIPPED_UNCHANGED   // content hash matched — nothing re-embedded, nothing billed
    }
}