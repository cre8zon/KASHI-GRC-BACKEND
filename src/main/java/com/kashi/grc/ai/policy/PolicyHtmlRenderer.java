package com.kashi.grc.ai.policy;

import com.kashi.grc.ai.policy.PolicyAiDtos.DraftResponse;
import com.kashi.grc.ai.policy.PolicyAiDtos.Definition;
import com.kashi.grc.ai.policy.PolicyAiDtos.RoleResponsibility;
import com.kashi.grc.ai.policy.PolicyAiDtos.Section;
import org.springframework.stereotype.Component;

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
 */
@Component
public class PolicyHtmlRenderer {

    public String render(DraftResponse d) {
        StringBuilder h = new StringBuilder();

        if (notBlank(d.getTitle())) h.append("<h1>").append(esc(d.getTitle())).append("</h1>");

        if (notBlank(d.getPurpose())) {
            h.append("<h2>Purpose</h2>").append(paragraphs(d.getPurpose()));
        }
        if (notBlank(d.getScope())) {
            h.append("<h2>Scope</h2>").append(paragraphs(d.getScope()));
        }

        if (d.getDefinitions() != null && !d.getDefinitions().isEmpty()) {
            h.append("<h2>Definitions</h2><table><tbody>")
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
                if (notBlank(s.getHeading())) h.append("<h2>").append(esc(s.getHeading())).append("</h2>");
                h.append(paragraphs(s.getBody()));
            }
        }

        if (d.getRoles() != null && !d.getRoles().isEmpty()) {
            h.append("<h2>Roles and Responsibilities</h2><table><tbody>")
             .append("<tr><th>Role</th><th>Responsibility</th></tr>");
            for (RoleResponsibility r : d.getRoles()) {
                h.append("<tr><td>").append(esc(r.getRole())).append("</td><td>")
                 .append(esc(r.getResponsibility())).append("</td></tr>");
            }
            h.append("</tbody></table>");
        }

        /*
         * Review and revision block. Auditors look for it, and a generated policy
         * without one is immediately identifiable as boilerplate.
         */
        h.append("<h2>Review and Revision</h2>")
         .append("<p>This policy is reviewed at least every ")
         .append(d.getSuggestedReviewMonths() == null ? 12 : d.getSuggestedReviewMonths())
         .append(" months, and following any material change to the organisation's ")
         .append("systems, obligations or risk profile.</p>");

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
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
