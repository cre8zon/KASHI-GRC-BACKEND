package com.kashi.grc.ai.rag;

import com.kashi.grc.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a document into retrievable passages.
 *
 * ── CHUNKING IS WHERE RAG QUALITY IS WON OR LOST ─────────────────────────────
 * Far more than model choice. Split naively at a fixed character count and you
 * get chunks beginning mid-sentence and clauses severed from the heading that
 * gives them meaning. Retrieval then returns a fragment that reads as
 * authoritative and is missing the qualifier that changed what it meant.
 *
 * Three things fix most of it, and all three are here:
 *
 *   1. HEADING AWARENESS. Policies are structured documents. Splitting on
 *      headings first keeps "3.2 Key Rotation" whole instead of straddling two
 *      chunks.
 *   2. SENTENCE BOUNDARIES. Within an oversized section, break at sentence ends,
 *      not at character N.
 *   3. HEADING BREADCRUMB. Every chunk carries its section path, so a retrieved
 *      fragment can be cited as "POL-03 §3.2 Key Rotation" rather than as
 *      anonymous text. Citation is what makes an auditor comfortable.
 *
 * ── OVERLAP ──────────────────────────────────────────────────────────────────
 * Consecutive chunks share a couple of hundred characters. It costs storage and
 * it rescues the case where the sentence that answers the query sits exactly on
 * a boundary. Cheap insurance against an invisible failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentChunker {

    private final AiProperties props;

    /** One passage plus the breadcrumb that lets it be cited. */
    public record Chunk(int index, String content, String sectionPath, int estimatedTokens) {}

    private static final Pattern HTML_HEADING =
            Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MD_HEADING =
            Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern SENTENCE_END =
            Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\"'\\u2018\\u201c])");

    /**
     * @param text        plain text, markdown, or HTML (TipTap output is HTML)
     * @param isHtml      true for contentBody straight out of the policy editor
     */
    public List<Chunk> chunk(String text, boolean isHtml) {
        if (text == null || text.isBlank()) return List.of();

        int maxChars = props.getRetrieval().getChunkSizeChars();
        int overlap  = props.getRetrieval().getChunkOverlapChars();

        List<Section> sections = isHtml ? splitHtmlByHeading(text) : splitMarkdownByHeading(text);
        List<Chunk> out = new ArrayList<>();
        int index = 0;

        for (Section s : sections) {
            String body = s.body.trim();
            if (body.isEmpty()) continue;

            if (body.length() <= maxChars) {
                out.add(new Chunk(index++, body, s.path, estimateTokens(body)));
                continue;
            }
            for (String piece : splitBySentence(body, maxChars, overlap)) {
                out.add(new Chunk(index++, piece, s.path, estimateTokens(piece)));
            }
        }

        log.debug("[AI-CHUNK] {} chars -> {} chunks across {} sections", text.length(), out.size(), sections.size());
        return out;
    }

    // ── Section splitting ─────────────────────────────────────────────────────

    private record Section(String path, String body) {}

    private List<Section> splitHtmlByHeading(String html) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HTML_HEADING.matcher(html);

        int lastEnd = 0;
        String currentPath = "";
        String[] crumbs = new String[7];

        while (m.find()) {
            if (m.start() > lastEnd) {
                String body = stripHtml(html.substring(lastEnd, m.start()));
                if (!body.isBlank()) sections.add(new Section(currentPath, body));
            }
            int level = Integer.parseInt(m.group(1));
            String heading = stripHtml(m.group(2)).trim();

            crumbs[level] = heading;
            for (int i = level + 1; i < crumbs.length; i++) crumbs[i] = null;   // deeper crumbs no longer apply

            StringBuilder path = new StringBuilder();
            for (int i = 1; i < crumbs.length; i++) {
                if (crumbs[i] != null) { if (path.length() > 0) path.append(" > "); path.append(crumbs[i]); }
            }
            currentPath = path.toString();
            lastEnd = m.end();
        }

        if (lastEnd < html.length()) {
            String body = stripHtml(html.substring(lastEnd));
            if (!body.isBlank()) sections.add(new Section(currentPath, body));
        }
        if (sections.isEmpty()) sections.add(new Section("", stripHtml(html)));
        return sections;
    }

    private List<Section> splitMarkdownByHeading(String text) {
        List<Section> sections = new ArrayList<>();
        Matcher m = MD_HEADING.matcher(text);

        int lastEnd = 0;
        String currentPath = "";
        String[] crumbs = new String[7];

        while (m.find()) {
            if (m.start() > lastEnd) {
                String body = text.substring(lastEnd, m.start()).trim();
                if (!body.isBlank()) sections.add(new Section(currentPath, body));
            }
            int level = m.group(1).length();
            crumbs[level] = m.group(2).trim();
            for (int i = level + 1; i < crumbs.length; i++) crumbs[i] = null;

            StringBuilder path = new StringBuilder();
            for (int i = 1; i < crumbs.length; i++) {
                if (crumbs[i] != null) { if (path.length() > 0) path.append(" > "); path.append(crumbs[i]); }
            }
            currentPath = path.toString();
            lastEnd = m.end();
        }

        if (lastEnd < text.length()) {
            String body = text.substring(lastEnd).trim();
            if (!body.isBlank()) sections.add(new Section(currentPath, body));
        }
        if (sections.isEmpty()) sections.add(new Section("", text));
        return sections;
    }

    // ── Sentence-aware windowing ──────────────────────────────────────────────

    private List<String> splitBySentence(String text, int maxChars, int overlap) {
        List<String> out = new ArrayList<>();
        String[] sentences = SENTENCE_END.split(text);

        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            // A single sentence longer than the window (a monster table cell, a
            // wall of legalese with no full stops) is hard-cut. Rare, and better
            // than dropping it.
            if (sentence.length() > maxChars) {
                if (current.length() > 0) { out.add(current.toString().trim()); current.setLength(0); }
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    out.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
                continue;
            }
            if (current.length() + sentence.length() + 1 > maxChars) {
                out.add(current.toString().trim());
                String tail = current.length() > overlap
                        ? current.substring(current.length() - overlap) : current.toString();
                current = new StringBuilder(tail).append(' ');
            }
            current.append(sentence).append(' ');
        }
        if (current.toString().trim().length() > 0) out.add(current.toString().trim());
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Minimal HTML to text. Deliberately not a parser: TipTap emits a small,
     * predictable subset and pulling in a full HTML library for it would be
     * another dependency on the supply-chain list for no gain.
     */
    public String stripHtml(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\n")
                .replaceAll("(?i)<li[^>]*>", "- ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** ~4 chars per token. Fine for budgeting; never used for billing. */
    public int estimateTokens(String s) {
        return s == null ? 0 : Math.max(1, s.length() / 4);
    }
}
