package com.kashi.grc.ai.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The model never invents an identifier. Ever.
 *
 * ── WHY THIS IS THE MOST IMPORTANT GUARDRAIL IN A GRC PRODUCT ────────────────
 * A hallucinated sentence in a generated policy is embarrassing and a human
 * reviewer catches it. A hallucinated CONTROL MAPPING is different in kind. It
 * asserts that a policy satisfies "ISO 27001 A.9.4.2" — a plausible-looking
 * reference that a reviewer skims past — and that assertion then flows into
 * coverage dashboards, audit evidence packages and eventually a customer's
 * certification submission. You have manufactured a compliance claim that is
 * false, and you have done it in the system of record that exists to prevent
 * exactly that.
 *
 * There is no amount of prompt tuning that makes this acceptable to leave to the
 * model's discretion. So it is not left there:
 *
 *   1. The prompt supplies an ENUMERATED candidate set. The model picks; it
 *      does not recall.
 *   2. Every returned reference is checked against that set here.
 *   3. Anything outside the set is dropped, and if the whole response is
 *      fabricated the response is rejected outright.
 *
 * ── STRICT VS LENIENT ────────────────────────────────────────────────────────
 * strict() rejects the response if ANY reference is fabricated — the right
 * behaviour when the references are the entire payload, as in a mapping call.
 * filter() drops the bad ones and returns the rest, which suits a draft where
 * one stray framework mention should not discard three good pages of prose. The
 * dropped values are always logged and always reported to the caller, because
 * "we silently deleted part of the AI's answer" is not something to discover
 * later.
 */
@Slf4j
@Component
public class ReferenceIntegrityGuard {

    public record IntegrityResult(List<String> valid, List<String> fabricated) {
        public boolean clean()      { return fabricated.isEmpty(); }
        public boolean anyValid()   { return !valid.isEmpty(); }
    }

    /**
     * Compare returned references against the allowed set.
     * Case-insensitive and whitespace-tolerant: the model reliably returns
     * "iso 27001 a.9.4.2" for a candidate written "ISO 27001 A.9.4.2", and
     * rejecting that is pedantry rather than safety.
     */
    public IntegrityResult check(Collection<String> returned, Collection<String> allowed) {
        Set<String> canonicalAllowed = new LinkedHashSet<>();
        for (String a : allowed) if (a != null) canonicalAllowed.add(canonical(a));

        List<String> valid = new ArrayList<>();
        List<String> bad   = new ArrayList<>();

        for (String r : returned) {
            if (r == null || r.isBlank()) continue;
            if (canonicalAllowed.contains(canonical(r))) valid.add(r.trim());
            else bad.add(r.trim());
        }

        if (!bad.isEmpty()) {
            log.warn("[AI-GUARD] model returned {} reference(s) outside the candidate set: {}", bad.size(), bad);
        }
        return new IntegrityResult(valid, bad);
    }

    /** Any fabrication rejects the whole response. Use where the references ARE the payload. */
    public List<String> strict(Collection<String> returned, Collection<String> allowed, String kind) {
        IntegrityResult r = check(returned, allowed);
        if (!r.clean()) throw GuardrailException.fabricatedIds(r.fabricated(), kind);
        return r.valid();
    }

    /** Drop the fabricated ones, keep the rest. Use where they are one field among many. */
    public IntegrityResult filter(Collection<String> returned, Collection<String> allowed) {
        return check(returned, allowed);
    }

    /**
     * Pull a string field out of every element of a JSON array — the shape a
     * mapping response actually arrives in:
     *   { "mappings": [ { "controlCode": "IAM-02", "rationale": "..." } ] }
     */
    public List<String> extractField(JsonNode arrayNode, String field) {
        List<String> out = new ArrayList<>();
        if (arrayNode == null || !arrayNode.isArray()) return out;
        for (JsonNode n : arrayNode) {
            JsonNode v = n.path(field);
            if (v.isTextual() && !v.asText().isBlank()) out.add(v.asText());
        }
        return out;
    }

    private String canonical(String s) {
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
