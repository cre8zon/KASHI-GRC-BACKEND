package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kashi.grc.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that reads the block array.
 *
 * ── THE BLOCK SCHEMA, AND WHY IT IS FIXED NOW ────────────────────────────────
 * Retrofitting a block type after launch means migrating every published post
 * that could have used it, and in practice means never doing it. So the schema
 * carries all fifteen types from the start even though the first release only
 * renders six:
 *
 *   paragraph  heading  list  quote  image  code
 *   tldr  callout  table  faq  steps  cta  download  embed  comparison
 *
 * The four that matter most for reach are tldr, callout, faq and comparison.
 * Those are the ones that get extracted into featured snippets and AI search
 * summaries, because they are self-contained answers rather than prose that
 * only makes sense in sequence. A blog that ships without them is a blog that
 * ranks and is never quoted.
 *
 * ── UNKNOWN TYPES DO NOT THROW ───────────────────────────────────────────────
 * Both here and in the renderer, an unrecognised block is skipped with a
 * warning. That lets the backend ship a new type before the front end knows
 * about it, which is the only order in which those two can be deployed
 * independently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockService {

    private final ObjectMapper mapper;
    private final SlugService slugService;

    /** Blocks whose text counts toward read time and the word count. */
    private static final Set<String> TEXT_BLOCKS = Set.of(
            "paragraph", "heading", "list", "quote", "tldr", "callout",
            "table", "faq", "steps", "download", "cta");

    private static final Pattern TAG   = Pattern.compile("<[^>]+>");
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Average adult reading speed for technical prose. Deliberately not 250. */
    private static final int WORDS_PER_MINUTE = 200;

    // ── parsing ──────────────────────────────────────────────────────────────

    public ArrayNode parse(String json) {
        if (json == null || json.isBlank()) return mapper.createArrayNode();
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) {
                throw new BusinessException("CONTENT_BLOCKS_INVALID",
                        "contentBlocks must be a JSON array of blocks");
            }
            return (ArrayNode) node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("CONTENT_BLOCKS_INVALID",
                    "contentBlocks is not valid JSON: " + e.getMessage());
        }
    }

    public String write(ArrayNode blocks) {
        try {
            return mapper.writeValueAsString(blocks);
        } catch (Exception e) {
            throw new BusinessException("CONTENT_BLOCKS_INVALID", "could not serialise blocks");
        }
    }

    // ── derived values ───────────────────────────────────────────────────────

    /**
     * All human-readable text, flattened. Read time, word count and the search
     * index all derive from this, so they can never disagree with each other.
     */
    public String textOf(ArrayNode blocks) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : blocks) {
            String type = b.path("type").asText("");
            if (!TEXT_BLOCKS.contains(type)) continue;

            switch (type) {
                case "paragraph", "callout" -> sb.append(strip(b.path("html").asText(""))).append(' ');
                case "heading" -> sb.append(b.path("text").asText("")).append(' ');
                case "quote"   -> sb.append(b.path("text").asText("")).append(' ');
                case "list", "tldr" -> b.path("items").forEach(i -> sb.append(i.asText("")).append(' '));
                case "table" -> {
                    b.path("headers").forEach(h -> sb.append(h.asText("")).append(' '));
                    b.path("rows").forEach(row -> row.forEach(c -> sb.append(c.asText("")).append(' ')));
                }
                case "faq" -> b.path("items").forEach(i ->
                        sb.append(i.path("q").asText("")).append(' ').append(i.path("a").asText("")).append(' '));
                case "steps" -> b.path("items").forEach(i ->
                        sb.append(i.path("heading").asText("")).append(' ')
                          .append(strip(i.path("html").asText(""))).append(' '));
                case "download", "cta" -> sb.append(b.path("title").asText(""))
                        .append(' ').append(b.path("heading").asText(""))
                        .append(' ').append(strip(b.path("body").asText(""))).append(' ');
                default -> { }
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    public int wordCount(ArrayNode blocks) {
        String text = textOf(blocks);
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    /** Minimum one. A two-hundred-word changelog entry reading "0 min" looks broken. */
    public int readTimeMinutes(ArrayNode blocks) {
        return Math.max(1, (int) Math.ceil(wordCount(blocks) / (double) WORDS_PER_MINUTE));
    }

    /**
     * SHA-256 over the canonical serialisation.
     *
     * This one value decides whether contentUpdatedAt moves and whether a
     * revision is written. Autosave fires every two seconds; without it every
     * keystroke pause would advertise the article as freshly updated and store
     * another @Lob snapshot.
     */
    public String hash(ArrayNode blocks) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(blocks));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("could not hash content blocks", e);
        }
    }

    // ── normalisation ────────────────────────────────────────────────────────

    /**
     * Fill in anchors and demote stray H1s, in place, before saving.
     *
     * Exactly one H1 per page is not a style preference — two of them makes the
     * page's subject ambiguous to a crawler. The editor could warn instead, but
     * a warning an editor can ignore at 6pm on a Friday is not an invariant.
     * Demoting here means the stored document is always correct, so the public
     * renderer never has to compensate.
     */
    public ArrayNode normalise(ArrayNode blocks) {
        Set<String> usedAnchors = new LinkedHashSet<>();
        for (JsonNode node : blocks) {
            if (!(node instanceof ObjectNode b)) continue;
            if (!"heading".equals(b.path("type").asText())) continue;

            if (b.path("level").asInt(2) < 2) b.put("level", 2);

            String anchor = b.path("anchor").asText("");
            if (anchor.isBlank()) anchor = slugService.anchorFor(b.path("text").asText(""));
            if (anchor.isBlank()) anchor = "section";

            String candidate = anchor;
            int n = 2;
            while (!usedAnchors.add(candidate)) candidate = anchor + "-" + n++;
            b.put("anchor", candidate);
        }
        return blocks;
    }

    // ── extraction ───────────────────────────────────────────────────────────

    public record Heading(int level, String text, String anchor) {}

    public List<Heading> headings(ArrayNode blocks) {
        List<Heading> out = new ArrayList<>();
        for (JsonNode b : blocks) {
            if (!"heading".equals(b.path("type").asText())) continue;
            out.add(new Heading(b.path("level").asInt(2),
                    b.path("text").asText(""), b.path("anchor").asText("")));
        }
        return out;
    }

    public record Faq(String question, String answer) {}

    /** Feeds FAQPage schema. Any faq block on any content type contributes. */
    public List<Faq> faqs(ArrayNode blocks) {
        List<Faq> out = new ArrayList<>();
        for (JsonNode b : blocks) {
            if (!"faq".equals(b.path("type").asText())) continue;
            b.path("items").forEach(i ->
                    out.add(new Faq(i.path("q").asText(""), i.path("a").asText(""))));
        }
        return out;
    }

    /** ContentMedia ids referenced by image, download or hero blocks. */
    public List<Long> mediaIds(ArrayNode blocks) {
        List<Long> out = new ArrayList<>();
        for (JsonNode b : blocks) {
            String type = b.path("type").asText("");
            if (("image".equals(type) || "download".equals(type)) && b.hasNonNull("mediaId")) {
                out.add(b.path("mediaId").asLong());
            }
        }
        return out;
    }

    public record ExtractedLink(String href, String anchorText) {}

    /**
     * Every anchor in every HTML-bearing block, plus CTA button targets.
     * Feeds the link graph, which feeds the orphan report and the link checker.
     */
    public List<ExtractedLink> links(ArrayNode blocks) {
        List<ExtractedLink> out = new ArrayList<>();
        for (JsonNode b : blocks) {
            String type = b.path("type").asText("");
            List<String> htmlFields = new ArrayList<>();
            switch (type) {
                case "paragraph", "callout" -> htmlFields.add(b.path("html").asText(""));
                case "steps" -> b.path("items").forEach(i -> htmlFields.add(i.path("html").asText("")));
                case "faq"   -> b.path("items").forEach(i -> htmlFields.add(i.path("a").asText("")));
                case "cta"   -> {
                    String href = b.path("buttonHref").asText("");
                    if (!href.isBlank()) out.add(new ExtractedLink(href, b.path("buttonText").asText("")));
                }
                default -> { }
            }
            for (String html : htmlFields) {
                Matcher m = ANCHOR.matcher(html);
                while (m.find()) out.add(new ExtractedLink(m.group(1), strip(m.group(2))));
            }
        }
        return out;
    }

    /** Image blocks whose media is missing alt text. Publish is blocked on these. */
    public List<Long> imageMediaIds(ArrayNode blocks) {
        List<Long> out = new ArrayList<>();
        for (JsonNode b : blocks) {
            if ("image".equals(b.path("type").asText()) && b.hasNonNull("mediaId")) {
                out.add(b.path("mediaId").asLong());
            }
        }
        return out;
    }

    public boolean hasBlockOfType(ArrayNode blocks, String type) {
        for (JsonNode b : blocks) if (type.equals(b.path("type").asText())) return true;
        return false;
    }

    private String strip(String html) {
        if (html == null) return "";
        return TAG.matcher(html).replaceAll(" ");
    }

    private static String utf8(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
