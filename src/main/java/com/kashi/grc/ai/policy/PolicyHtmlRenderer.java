package com.kashi.grc.ai.policy;

import com.kashi.grc.ai.policy.PolicyAiDtos.DraftResponse;
import com.kashi.grc.ai.policy.PolicyAiDtos.Definition;
import com.kashi.grc.ai.policy.PolicyAiDtos.RoleResponsibility;
import com.kashi.grc.ai.policy.PolicyAiDtos.Section;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.SortedSet;
import java.util.regex.Pattern;

/**
 * Structured draft -> HTML for TipTap.
 *
 * ── WHY THE MODEL RETURNS JSON AND NOT HTML ──────────────────────────────────
 * Asking for HTML directly seems simpler and costs you everything downstream:
 *
 *   - REGENERATION. With structure, "rewrite section 4" is a targeted call on
 *     one object. With a blob, it is a diff-and-splice problem.
 *   - VALIDATION. You can assert that six sections exist and each addresses a
 *     control. You cannot assert that about a string.
 *   - COVERAGE. Section-to-control mapping is what drives the coverage view —
 *     it only exists if the model emitted it as data.
 *   - SAFETY. Model-authored HTML goes into a rich-text editor. Constraining
 *     generation to text fields and building the markup here means there is no
 *     path from model output to arbitrary markup.
 *
 * ── OUTPUT SHAPE ─────────────────────────────────────────────────────────────
 * Only the subset TipTap's configured extensions understand: h1-h3, p, ul/li,
 * table/tr/td/th, strong, em. Anything outside that renders as an unstyled
 * artefact or is silently dropped by ProseMirror on load.
 *
 * ── HOUSE STRUCTURE, MATCHING THE POLICY LIBRARY ─────────────────────────────
 * A generated policy must look like the ones in the library, or a reviewer can
 * spot the AI-written ones at a glance. So the renderer, not the model, adds:
 *
 *   - the document-control line under the title, written as variables
 *     ({{policy_ref}}, {{policy_version}}, {{policy_owner}}, {{review_cycle}})
 *     that PolicyVariableResolver fills when the policy is read — so the line
 *     stays right when the policy is renumbered, versioned or re-owned;
 *   - numbered section headings;
 *   - "Compliance, Review and Enforcement", with the exception route through
 *     the exceptions register;
 *   - "Framework References", built from the control mappings, not written by
 *     the model, so it can only cite clauses the catalogue actually maps.
 *
 * Fixed text, not model text: these blocks are the same in every policy and an
 * auditor should find them in the same words each time. A model-written
 * section with one of these headings is dropped in favour of the house block.
 */
@Component
public class PolicyHtmlRenderer {

    /**
     * Headings the renderer owns. Whole-heading match only: a model section
     * called "Compliance" is replaced by the house block, but "Patch exception
     * handling" or "Compliance monitoring" is real content and is kept.
     */
    private static final Pattern HOUSE_HEADING = Pattern.compile(
            "(?i)^\\s*(\\d+[.)]\\s*)?(policy\\s+)?(compliance|enforcement|compliance and enforcement|"
                    + "compliance, review and enforcement|exceptions|policy exceptions|review and revision|"
                    + "policy review|review|framework references?|references)\\s*$");

    public String render(DraftResponse d) {
        return render(d, null, null);
    }

    public String render(DraftResponse d, Map<String, SortedSet<String>> references) {
        return render(d, references, null);
    }

    /**
     * @param references framework -> clauses, from PolicyContextAssembler.frameworkReferences;
     *                   null or empty omits the references section
     * @param roleKeys   register role title -> key (PolicyRoleRegister). A role in
     *                   the table whose title matches gets a "Current holder" cell
     *                   written as {{role_<key>}}, filled when the policy is read.
     */
    public String render(DraftResponse d, Map<String, SortedSet<String>> references,
                         Map<String, String> roleKeys) {
        StringBuilder h = new StringBuilder();
        int[] n = {0};

        if (notBlank(d.getTitle())) h.append("<h1>").append(esc(d.getTitle())).append("</h1>");

        h.append("<p><strong>Policy Reference:</strong> {{policy_ref}}  |  ")
                .append("<strong>Version:</strong> {{policy_version}}  |  ")
                .append("<strong>Owner:</strong> {{policy_owner}}  |  ")
                .append("<strong>Review cycle:</strong> {{review_cycle}}</p>");

        if (notBlank(d.getPurpose())) {
            h.append(h2(n, "Purpose")).append(paragraphs(d.getPurpose()));
        }
        if (notBlank(d.getScope())) {
            h.append(h2(n, "Scope")).append(paragraphs(d.getScope()));
        }

        if (d.getDefinitions() != null && !d.getDefinitions().isEmpty()) {
            h.append(h2(n, "Definitions")).append("<table><tbody>")
                    .append("<tr><th>Term</th><th>Meaning</th></tr>");
            for (Definition def : d.getDefinitions()) {
                h.append("<tr><td>").append(esc(def.getTerm())).append("</td><td>")
                        .append(esc(def.getMeaning())).append("</td></tr>");
            }
            h.append("</tbody></table>");
        }

        if (d.getSections() != null) {
            for (Section s : d.getSections()) {
                if (!notBlank(s.getHeading()) && !notBlank(s.getBody())) continue;
                if (notBlank(s.getHeading()) && HOUSE_HEADING.matcher(stripNumber(s.getHeading())).matches()) continue;
                if (notBlank(s.getHeading())) h.append(h2(n, s.getHeading()));
                h.append(paragraphs(s.getBody()));
            }
        }

        if (d.getRoles() != null && !d.getRoles().isEmpty()) {
            Map<String, String> keys = new java.util.HashMap<>();
            if (roleKeys != null) roleKeys.forEach((t, k) -> keys.put(t.trim().toLowerCase(), k));
            boolean holders = d.getRoles().stream()
                    .anyMatch(r -> r.getRole() != null && keys.containsKey(r.getRole().trim().toLowerCase()));

            h.append(h2(n, "Roles and Responsibilities")).append("<table><tbody>")
                    .append("<tr><th>Role</th>").append(holders ? "<th>Current holder</th>" : "")
                    .append("<th>Responsibility</th></tr>");
            for (RoleResponsibility r : d.getRoles()) {
                h.append("<tr><td>").append(esc(r.getRole())).append("</td>");
                if (holders) {
                    String k = r.getRole() == null ? null : keys.get(r.getRole().trim().toLowerCase());
                    h.append("<td>").append(k == null ? "—" : "{{role_" + k + "}}").append("</td>");
                }
                h.append("<td>").append(esc(r.getResponsibility())).append("</td></tr>");
            }
            h.append("</tbody></table>");
        }

        /*
         * Review and revision block. Auditors look for it, and a generated policy
         * without one is immediately identifiable as boilerplate.
         */
        int months = d.getSuggestedReviewMonths() == null ? 12 : d.getSuggestedReviewMonths();
        h.append(h2(n, "Compliance, Review and Enforcement"))
                .append("<p>Compliance with this policy is mandatory. Violations may result in disciplinary ")
                .append("action up to and including termination of employment or contract, and may carry ")
                .append("legal consequences.</p>")
                .append("<p>This policy is reviewed at least every ").append(months)
                .append(" months by the policy owner, {{policy_owner}}, and after any material change to ")
                .append("the organisation's systems, obligations or risk profile.</p>")
                .append("<p>Exceptions require a documented risk assessment and written approval from the ")
                .append("policy owner. Approved exceptions are recorded in the exceptions register and ")
                .append("reviewed at each policy review.</p>");

        if (references != null && !references.isEmpty()) {
            h.append(h2(n, "Framework References"))
                    .append("<p>This policy supports the following requirements:</p><ul>");
            references.forEach((fw, clauses) -> h.append("<li><strong>").append(esc(label(fw)))
                    .append(":</strong> ").append(esc(String.join(", ", clauses))).append("</li>"));
            h.append("</ul>");
        }

        return h.toString();
    }

    /**
     * Body text to paragraphs and bullets.
     *
     * The bullet detection matters: models emit "- item" lines constantly, and
     * without this they render as literal hyphens inside a paragraph, which is
     * the single most obvious tell that a document came out of a text box.
     */
    private String paragraphs(String text) {
        if (!notBlank(text)) return "";
        StringBuilder out = new StringBuilder();
        boolean inList = false;

        for (String block : text.split("\\n")) {
            String line = block.trim();
            if (line.isEmpty()) continue;

            boolean bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ");
            if (bullet) {
                if (!inList) { out.append("<ul>"); inList = true; }
                out.append("<li>").append(esc(line.substring(2).trim())).append("</li>");
            } else {
                if (inList) { out.append("</ul>"); inList = false; }
                out.append("<p>").append(esc(line)).append("</p>");
            }
        }
        if (inList) out.append("</ul>");
        return out.toString();
    }

    /**
     * Escape everything. Model output is never trusted as markup — this is the
     * boundary where a generated document could otherwise carry script into an
     * editor rendered inside an authenticated session.
     */
    private String h2(int[] n, String heading) {
        return "<h2>" + (++n[0]) + ". " + esc(stripNumber(heading)) + "</h2>";
    }

    /**
     * The model often numbers its own headings ("1. Secure Design", "Section 2:
     * Code Review"). Sections are numbered here, so a model number would print
     * twice — "4. 1. Secure Design".
     */
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^\\s*(?:section\\s+\\d+(?:\\.\\d+)*\\s*[.):\\-–—]?|\\d+(?:\\.\\d+)+\\.?|\\d+\\s*[.):\\-–—]|[ivxlc]+\\s*[.):\\-–—])\\s+",
            Pattern.CASE_INSENSITIVE);

    static String stripNumber(String heading) {
        if (heading == null) return null;
        String s = LEADING_NUMBER.matcher(heading).replaceFirst("");
        return s.isBlank() ? heading.trim() : s;
    }

    /** Framework codes as people write them. */
    private static String label(String fw) {
        return switch (fw.toUpperCase()) {
            case "ISO27001" -> "ISO/IEC 27001:2022";
            case "SOC2"     -> "SOC 2";
            case "DPDPA"    -> "DPDP Act 2023";
            case "CERTIN"   -> "CERT-In Directions";
            default -> fw;
        };
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }
}