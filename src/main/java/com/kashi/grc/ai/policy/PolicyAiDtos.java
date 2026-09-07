package com.kashi.grc.ai.policy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request/response shapes for the policy AI endpoints.
 *
 * Grouped in one file per the CommonControlDtos convention.
 */
public final class PolicyAiDtos {

    private PolicyAiDtos() {}

    // ── Metadata suggestion — the CREATE path ────────────────────────────────

    /**
     * Free-text intent, before a policy row exists.
     *
     * ── WHY THIS STEP EXISTS ─────────────────────────────────────────────────
     * Asking someone to fill in title, framework refs, control tags, owner team
     * and review cadence BEFORE they have written anything is asking them to
     * make six decisions about a document that does not exist yet. Most people
     * guess, and the guesses become the grounding for everything after.
     *
     * One sentence of intent is easier to give and produces better metadata,
     * because the model picks control codes from the real catalogue rather than
     * the user picking from a dropdown of 134.
     */
    @Data
    public static class MetadataRequest {
        /** "we need something covering laptop encryption and lost devices" */
        @NotBlank @Size(max = 2000)
        private String intent;

        /** Narrow the control candidates. Empty = the tenant's whole library. */
        private List<String> frameworks;

        private Long templateId;
    }

    /**
     * Everything needed to create the policy row. Every field is EDITABLE in the
     * UI before creation — this is a suggestion, not a decision.
     */
    @Data
    public static class MetadataResponse {
        private String  title;
        private String  description;
        private String  policyRef;
        private List<String> frameworkRefs;
        private List<MappingSuggestion> suggestedControls;
        private Integer reviewFrequencyMonths;
        private String  ownerTeam;
        /** Why these controls, in one line — shown so the user can sanity-check. */
        private String  rationale;
        private Long    interactionId;
        private List<String> warnings;
    }

    // ── Draft generation ──────────────────────────────────────────────────────

    @Data
    public static class DraftRequest {
        /**
         * The policy title.
         *
         * On the EDIT path this is read from the existing AuditPolicy and the UI
         * does not ask for it — the policy already has a title and asking again
         * invites two sources of truth. On the CREATE path it comes from the
         * metadata step, after the user has confirmed it.
         */
        @NotBlank @Size(max = 500)
        private String title;

        /**
         * Set on the edit path so the draft can be grounded in the policy's own
         * metadata and existing content rather than only in the request.
         */
        private Long policyId;

        /**
         * Control codes the policy must satisfy. These become the enumerated
         * candidate set — the model picks from them and cannot invent others.
         */
        private List<String> controlCodes;

        /** Frameworks to align to: SOC2, ISO27001, DPDP. Narrows the language used. */
        private List<String> frameworks;

        /** Optional global AuditPolicy to adapt rather than write from nothing. */
        private Long sourcePolicyId;

        /** Extra instructions from the user: "keep it under three pages", "we are fully remote". */
        @Size(max = 2000)
        private String additionalInstructions;

        /** Scope control grounding to an audit template's actual scope. Optional. */
        private Long templateId;

        /** Skip the critique pass for a faster, cheaper draft. */
        private boolean quickMode = false;
    }

    /** What the pipeline produces. Structured so a single section can be regenerated. */
    @Data
    public static class DraftResponse {
        private String  title;
        private String  purpose;
        private String  scope;
        private List<Section> sections;
        private List<Definition> definitions;
        private List<RoleResponsibility> roles;
        private List<MappingSuggestion> suggestedControls;
        private Integer suggestedReviewMonths;
        private String  policyRef;

        /** Rendered HTML for TipTap. The editor consumes this; the structure stays for regeneration. */
        private String  contentHtml;

        // ── Provenance. Non-negotiable in a compliance product. ───────────────
        private Long         interactionId;
        private String       correlationId;
        private List<String> citations;
        private List<String> warnings;
        private String       model;
        private Integer      tokensUsed;
        private Boolean      groundedInRetrieval;
    }

    @Data
    public static class Section {
        private String heading;
        private String body;
        /** Control codes this section addresses — drives per-section coverage display. */
        private List<String> addressesControls;
    }

    @Data public static class Definition { private String term; private String meaning; }

    @Data public static class RoleResponsibility { private String role; private String responsibility; }

    /** A proposed mapping. Always requires human acceptance; never written automatically. */
    @Data
    public static class MappingSuggestion {
        private String  controlCode;
        private String  controlTitle;
        private String  rationale;
        private Double  confidence;
        /** Section of the policy that satisfies it — makes the claim checkable. */
        private String  evidenceSection;
    }

    // ── Section rewrite ───────────────────────────────────────────────────────

    @Data
    public static class RewriteRequest {
        @NotNull  private Long   policyId;
        @NotBlank @Size(max = 20000) private String selectedText;
        /** SIMPLIFY | EXPAND | FORMALISE | SHORTEN | FIX_GRAMMAR | CUSTOM */
        @NotBlank private String  mode;
        @Size(max = 1000) private String customInstruction;
        /** Surrounding text so the rewrite fits its neighbours. */
        @Size(max = 4000) private String surroundingContext;
    }

    @Data
    public static class RewriteResponse {
        private String  rewrittenText;
        private String  originalText;
        private Long    interactionId;
        private String  explanation;
    }

    // ── Control mapping ───────────────────────────────────────────────────────

    @Data
    public static class MappingRequest {
        @NotNull private Long policyId;
        /** Restrict candidates to these frameworks. Empty = everything in the tenant's library. */
        private List<String> frameworks;

        /**
         * Preferred. Scopes candidates to the controls actually on this audit
         * template via template -> section -> control. For KashiGRC ISO
         * 27001:2022 that is 55 in-scope controls rather than the 116 reachable
         * by framework tag, which is both a better candidate set and a better
         * reviewer experience. Falls back to framework scope when absent.
         */
        private Long templateId;

        private Integer maxSuggestions;
    }

    @Data
    public static class MappingResponse {
        private List<MappingSuggestion> suggestions;
        private Long   interactionId;
        private String correlationId;
        private Integer candidatesConsidered;
        private List<String> warnings;
    }

    // ── Gap analysis ──────────────────────────────────────────────────────────

    @Data
    public static class GapRequest {
        @NotNull private Long policyId;
        @NotBlank private String framework;
        private List<String> controlCodes;
    }

    @Data
    public static class GapResponse {
        private List<Gap> gaps;
        private Double    coverageScore;
        private String    summary;
        private Long      interactionId;
        private List<String> citations;
    }

    @Data
    public static class Gap {
        private String controlCode;
        private String controlTitle;
        /** MISSING | PARTIAL | AMBIGUOUS */
        private String severity;
        private String whatIsExpected;
        private String whatIsMissing;
        private String suggestedText;
    }

    // ── Clause explanation ────────────────────────────────────────────────────

    @Data
    public static class ExplainRequest {
        @NotNull  private Long   policyId;
        @NotBlank @Size(max = 10000) private String clause;
        /** REVIEWER | EMPLOYEE | AUDITOR — changes register and depth. */
        private String audience;
    }

    @Data
    public static class ExplainResponse {
        private String explanation;
        private List<String> implications;
        private Long   interactionId;
    }
}