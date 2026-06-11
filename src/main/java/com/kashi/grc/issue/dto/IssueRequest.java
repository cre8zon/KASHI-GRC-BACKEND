package com.kashi.grc.issue.dto;

import com.kashi.grc.issue.domain.Issue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Request DTO for manual issue creation — INTERNAL and EXTERNAL types.
 * Used by POST /v1/issues from the frontend issue management UI.
 *
 * For AUTOMATED ingestion via external tools, use IssueIngestRequest instead.
 */
@Data
public class IssueRequest {

    // ── Core ──────────────────────────────────────────────────────────────────

    @NotBlank
    private String title;

    private String description;

    @NotNull
    private Issue.IssueType issueType;

    @NotNull
    private Issue.Severity severity;

    private String category;

    private String sourceModule;   // e.g. AUDIT, TPRM, RISK

    // ── Source linkage ────────────────────────────────────────────────────────

    private String sourceEntityType;
    private Long   sourceEntityId;
    private String sourceDescription;

    // ── Ownership ─────────────────────────────────────────────────────────────

    private Long ownerId;

    // ── SLA ───────────────────────────────────────────────────────────────────

    /** Explicit due date — if null, computed from severity SLA matrix by service */
    /** Accepts date-only (yyyy-MM-dd) or datetime — date picker sends date-only strings */
    @JsonDeserialize(using = FlexibleDateDeserializer.class)
    private LocalDateTime dueAt;

    /** Deserializes both 'yyyy-MM-dd' and 'yyyy-MM-ddTHH:mm:ss' into LocalDateTime */
    public static class FlexibleDateDeserializer extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<LocalDateTime> {
        public FlexibleDateDeserializer() { super(LocalDateTime.class); }
        @Override
        public LocalDateTime deserialize(com.fasterxml.jackson.core.JsonParser p,
                                         com.fasterxml.jackson.databind.DeserializationContext ctx) throws java.io.IOException {
            String s = p.getText();
            if (s == null || s.isBlank()) return null;
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay(); // yyyy-MM-dd
            return LocalDateTime.parse(s);  // yyyy-MM-ddTHH:mm:ss
        }
    }

    // ── Framework ─────────────────────────────────────────────────────────────

    private List<Long> linkedControlIds;
    private List<Long> linkedRiskIds;
    private String     frameworkRef;

    // ── Workflow ──────────────────────────────────────────────────────────────

    /**
     * ID of the Workflow blueprint to start.
     * If null, service looks up the default blueprint for this issueType.
     * Platform admin configures which blueprint is default per issueType
     * via the workflow admin UI.
     */
    private Long workflowId;

    // ── RCA (optional at creation, filled via workflow FILL step) ─────────────

    /** RCA method — e.g. 5WHY, FISHBONE, FMEA */
    private String rcaMethod;

    private String rootCauseCategory;

    /** Proximate / immediate cause — what directly failed */
    private String immediateCause;

    /** Underlying systemic cause — the 5-Why answer */
    private String rootCause;

    /**
     * Contributing factors — the MULTILINE_LIST field serializes entries as a JSON array.
     * e.g. ["Lack of training", "Insufficient monitoring"]
     */
    /** Accepts either a JSON array ["a","b"] or a plain string from the TAG field */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = FlexibleListDeserializer.class)
    private String contributingFactors;

    /** Deserializes both ["a","b"] arrays and plain strings into a comma-joined String */
    public static class FlexibleListDeserializer extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<String> {
        public FlexibleListDeserializer() { super(String.class); }
        @Override
        public String deserialize(com.fasterxml.jackson.core.JsonParser p,
                                  com.fasterxml.jackson.databind.DeserializationContext ctx) throws java.io.IOException {
            if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                // Column type is JSON — store as valid JSON array string
                java.util.List<String> items = new java.util.ArrayList<>();
                while (p.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY)
                    items.add(p.getValueAsString());
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items);
            }
            String val = p.getValueAsString();
            if (val == null || val.isBlank()) return null;
            // If plain string (not JSON array), wrap it in a JSON array
            if (!val.trim().startsWith("[")) {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(java.util.List.of(val));
            }
            return val;
        }
    }

    /** Whether this is a systemic issue affecting multiple areas */
    private Boolean isSystemic;

    /** Legacy RCA blob — accepted for backward compatibility */
    private String rcaJson;

    // ── Remediation ───────────────────────────────────────────────────────────

    private String remediationPlan;
    private String remediationType;
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = FlexibleDateDeserializer.class)
    private LocalDateTime remediatedAt;
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = FlexibleDateDeserializer.class)
    private LocalDateTime validatedAt;
    private Boolean acceptedRisk;
    private String  acceptedRiskNote;
    private String  closureSummary;
}