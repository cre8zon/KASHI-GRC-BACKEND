package com.kashi.grc.ai.guardrail;

import com.kashi.grc.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces personal identifiers with stable placeholders before a prompt leaves
 * the building, and puts them back afterwards.
 *
 * ── WHY REVERSIBLE AND NOT JUST STRIPPED ─────────────────────────────────────
 * Stripping breaks the output. If an incident-response policy needs to say
 * "report to security@acme.com", deleting the address produces a policy with a
 * hole in it. Substituting a stable token means the model reasons about
 * "[[EMAIL_1]]" as an email, writes it into the right sentence, and rehydrate()
 * restores the real value in the text the customer actually receives.
 *
 * ── WHAT THIS IS AND IS NOT ──────────────────────────────────────────────────
 * It is a defence-in-depth measure that reduces incidental personal data in
 * prompts and in ai_interactions. It is NOT a DLP product and will not catch
 * personal data embedded in prose ("our CFO Rajesh mentioned..."). Do not let it
 * be described to a customer as more than it is; the honest framing is
 * "identifiers are tokenised in transit", and that is already worth having.
 *
 * ── INDIA-SPECIFIC PATTERNS ──────────────────────────────────────────────────
 * Aadhaar and PAN are included because you are operating under the DPDP Act and
 * a customer uploading an HR policy with a sample PAN in it is not hypothetical.
 * Aadhaar deliberately does NOT verify the Verhoeff checksum — for redaction,
 * over-matching a 12-digit string is the safe direction to be wrong in.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiRedactor {

    private final AiProperties props;

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();
    static {
        PATTERNS.put("EMAIL",   Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        PATTERNS.put("IPV4",    Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\b"));
        PATTERNS.put("PAN",     Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b"));
        PATTERNS.put("AADHAAR", Pattern.compile("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b"));
        PATTERNS.put("CARD",    Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b"));
        PATTERNS.put("PHONE",   Pattern.compile("(?:\\+\\d{1,3}[ -]?)?\\(?\\d{3,5}\\)?[ -]?\\d{3,4}[ -]?\\d{4}\\b"));
    }

    /** Placeholder -> original. Handed back to rehydrate() and never persisted. */
    public record Redaction(String redactedText, Map<String, String> restoreMap, int replacements) {
        public boolean anyRedacted() { return replacements > 0; }
    }

    public Redaction redact(String text) {
        if (text == null || text.isBlank() || !props.getGuardrail().isRedactPii()) {
            return new Redaction(text, Map.of(), 0);
        }

        Map<String, String> restore = new LinkedHashMap<>();
        Map<String, String> seen    = new LinkedHashMap<>();   // same value -> same token
        String working = text;
        int count = 0;

        for (Map.Entry<String, Pattern> e : PATTERNS.entrySet()) {
            String kind = e.getKey();
            Matcher m   = e.getValue().matcher(working);
            StringBuilder sb = new StringBuilder();
            int idx = 0;

            while (m.find()) {
                String match = m.group();

                // CARD's loose digit pattern also matches ordinary long numbers;
                // requiring a plausible length keeps "1,234,567 records" intact.
                if ("CARD".equals(kind) && match.replaceAll("\\D", "").length() < 13) continue;

                /*
                 * Stable per value: the same address appearing eight times becomes
                 * [[EMAIL_1]] every time. Numbering each occurrence separately
                 * would tell the model there are eight different addresses, and it
                 * would helpfully write about eight different mailboxes.
                 */
                String token = seen.computeIfAbsent(match,
                        v -> "[[" + kind + "_" + (seen.size() + 1) + "]]");
                restore.put(token, match);

                sb.append(working, idx, m.start()).append(token);
                idx = m.end();
                count++;
            }
            sb.append(working.substring(idx));
            working = sb.toString();
        }

        if (count > 0) log.debug("[AI-GUARD] redacted {} identifier(s) across {} categories", count, seen.size());
        return new Redaction(working, restore, count);
    }

    /**
     * Restore originals in the model's output.
     *
     * Longest token first: without it "[[EMAIL_1]]" can be partially matched by
     * a shorter overlapping token and produce mangled output.
     */
    public String rehydrate(String text, Map<String, String> restoreMap) {
        if (text == null || restoreMap == null || restoreMap.isEmpty()) return text;
        String out = text;
        var keys = restoreMap.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        for (String token : keys) out = out.replace(token, restoreMap.get(token));
        return out;
    }
}
