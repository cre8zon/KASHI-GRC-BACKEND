package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.kashi.grc.ai.chat.AiChatService;
import com.kashi.grc.ai.chat.AiChatService.AiCall;
import com.kashi.grc.ai.chat.AiChatService.AiResult;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.guardrail.ReferenceIntegrityGuard;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI in the editor. Nothing here talks to a model directly.
 *
 * ── EVERYTHING GOES THROUGH AiChatService ────────────────────────────────────
 * Every call is an AiCall. That is not politeness about layering — it is how
 * this feature inherits budget enforcement, PII redaction, injection scanning,
 * JSON repair, usage recording and the ai_interactions audit row without
 * reimplementing any of them. A service that constructed its own LlmRequest
 * would silently opt out of all six, and nobody would notice until the bill or
 * the audit.
 *
 * ── TWO RULES, BOTH ENFORCED HERE AND NOT IN THE PROMPT ──────────────────────
 *
 * 1. CONTENT_INTERNAL_LINKS may only return slugs that exist. The published
 *    slug list is passed in as an enumerated candidate set and every returned
 *    slug is checked against it with ReferenceIntegrityGuard.strict() — the
 *    same treatment control codes get. The reasoning is identical: a
 *    hallucinated internal link is a 404 in production, published under our
 *    name, on a page whose whole argument is that we are careful.
 *
 * 2. Everything is a proposal. No method here writes to contentBlocks. They
 *    return something a person accepts or rejects in the editor. An AI that can
 *    edit the document directly is an AI whose mistakes are indistinguishable
 *    from the author's.
 *
 * ── TENANT ID IS NULL, DELIBERATELY ──────────────────────────────────────────
 * Content is platform-owned, so these calls carry no tenant. They are billed to
 * the platform's own budget rather than a customer's, which is correct: no
 * customer should pay for our marketing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentAiService {

    private final AiChatService aiChatService;
    private final ReferenceIntegrityGuard integrityGuard;
    private final PostRepository postRepository;
    private final BlockService blockService;
    private final UtilityService utilityService;
    private final ObjectMapper mapper;

    /** Every proposal carries the interaction id so the editor can post feedback against it. */
    public record Proposal(String taskType, JsonNode payload, Long interactionId,
                           List<String> warnings) {}

    // ── outline and drafting ─────────────────────────────────────────────────

    public Proposal outline(String topic, String persona, String targetKeyword) {
        AiResult r = aiChatService.completeJson(base("content.outline", TaskType.CONTENT_OUTLINE)
                .var("topic", topic)
                .var("persona", persona == null ? "compliance lead at a regulated Indian entity" : persona)
                .var("targetKeyword", targetKeyword == null ? topic : targetKeyword));
        return new Proposal("CONTENT_OUTLINE", r.json(), r.interactionId(), List.of());
    }

    /**
     * One section at a time, never the whole article.
     *
     * A model asked for 2,000 words produces 2,000 words of confident,
     * evenly-weighted, unsourced prose — which is exactly the texture a
     * compliance buyer has been trained to distrust. Section by section keeps a
     * person in the loop at every heading, which is both better writing and the
     * only honest way to publish claims under a named author's byline.
     */
    public Proposal draftSection(Long postId, String outlineJson, String heading) {
        Post post = post(postId);
        AiResult r = aiChatService.complete(base("content.draft-section", TaskType.CONTENT_DRAFT_SECTION)
                .var("title", post.getTitle())
                .var("outline", outlineJson)
                .var("heading", heading)
                .entity("CONTENT_POST", postId));
        return new Proposal("CONTENT_DRAFT_SECTION", textNode(r.content()), r.interactionId(), List.of());
    }

    public Proposal rewrite(Long postId, String selection, String instruction) {
        AiResult r = aiChatService.complete(base("content.rewrite", TaskType.CONTENT_REWRITE)
                .var("selection", selection)
                .var("instruction", instruction == null ? "tighten this without losing any specific claim" : instruction)
                .entity("CONTENT_POST", postId));
        return new Proposal("CONTENT_REWRITE", textNode(r.content()), r.interactionId(), List.of());
    }

    // ── SEO helpers ──────────────────────────────────────────────────────────

    public Proposal meta(Long postId) {
        Post post = post(postId);
        String opening = firstWords(post, 300);
        AiResult r = aiChatService.completeJson(base("content.meta", TaskType.CONTENT_META)
                .var("title", post.getTitle())
                .var("opening", opening)
                .var("focusKeyword", post.getFocusKeyword() == null ? "" : post.getFocusKeyword())
                .entity("CONTENT_POST", postId));
        return new Proposal("CONTENT_META", r.json(), r.interactionId(), List.of());
    }

    public Proposal tldr(Long postId) {
        Post post = post(postId);
        AiResult r = aiChatService.completeJson(base("content.tldr", TaskType.CONTENT_TLDR)
                .var("title", post.getTitle())
                .var("body", bodyText(post))
                .entity("CONTENT_POST", postId));
        return new Proposal("CONTENT_TLDR", r.json(), r.interactionId(), List.of());
    }

    public Proposal faq(Long postId) {
        Post post = post(postId);
        AiResult r = aiChatService.completeJson(base("content.faq", TaskType.CONTENT_FAQ)
                .var("title", post.getTitle())
                .var("body", bodyText(post))
                .entity("CONTENT_POST", postId));
        return new Proposal("CONTENT_FAQ", r.json(), r.interactionId(), List.of());
    }

    // ── internal links: the one with teeth ───────────────────────────────────

    /**
     * Suggest links from this draft to other published posts.
     *
     * The candidate set is enumerated in the prompt and enforced on the way
     * back. The model picks from a list; it never recalls a slug. Anything
     * outside the list rejects the whole response rather than being quietly
     * dropped, because in this task the slugs ARE the payload — a response with
     * half its links fabricated has nothing salvageable in it.
     */
    public Proposal internalLinks(Long postId) {
        Post post = post(postId);

        Map<String, String> slugToTitle = new LinkedHashMap<>();
        for (Object[] row : postRepository.findPublishedSlugCandidates(PostStatus.PUBLISHED)) {
            Long id = (Long) row[0];
            if (id.equals(postId)) continue;              // don't suggest linking to itself
            slugToTitle.put((String) row[1], (String) row[2]);
        }
        if (slugToTitle.isEmpty()) {
            throw new BusinessException("NO_LINK_CANDIDATES",
                    "There are no other published posts to link to yet");
        }

        StringBuilder candidates = new StringBuilder("AVAILABLE POSTS (copy slugs exactly):\n");
        slugToTitle.forEach((slug, title) -> candidates.append("  ").append(slug)
                .append(" — ").append(title).append('\n'));

        AiResult r = aiChatService.completeJson(base("content.internal-links", TaskType.CONTENT_INTERNAL_LINKS)
                .var("title", post.getTitle())
                .var("body", bodyText(post))
                .var("availablePosts", candidates.toString())
                .entity("CONTENT_POST", postId));

        List<String> returned = new ArrayList<>();
        r.json().path("links").forEach(l -> returned.add(l.path("slug").asText("")));

        // strict(): any fabrication rejects the response. Same as control codes.
        integrityGuard.strict(returned, slugToTitle.keySet(), "post slug");

        return new Proposal("CONTENT_INTERNAL_LINKS", r.json(), r.interactionId(), List.of());
    }

    // ── distribution ─────────────────────────────────────────────────────────

    public Proposal socialPost(Long postId, String channel) {
        Post post = post(postId);
        AiResult r = aiChatService.completeJson(base("content.social", TaskType.CONTENT_SOCIAL)
                .var("title", post.getTitle())
                .var("body", bodyText(post))
                .var("channel", channel == null ? "linkedin" : channel.toLowerCase())
                .var("url", "https://www.digiosec.com/blog/" + post.getSlug())
                .entity("CONTENT_POST", postId));
        return new Proposal("CONTENT_SOCIAL", r.json(), r.interactionId(), List.of());
    }

    // ── internals ────────────────────────────────────────────────────────────

    private AiCall base(String templateKey, TaskType taskType) {
        Long userId = null;
        try {
            var user = utilityService.getLoggedInDataContext();
            userId = user == null ? null : user.getId();
        } catch (Exception ignored) { }

        // tenant(null) on purpose — see the class comment.
        return AiCall.of(templateKey, taskType)
                .tenant(null)
                .user(userId)
                .pipeline("content-editor");
    }

    private Post post(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new BusinessException("POST_NOT_FOUND", "No post with id " + id));
    }

    private String bodyText(Post post) {
        ArrayNode blocks = blockService.parse(post.getContentBlocks());
        String text = blockService.textOf(blocks);
        if (text.isBlank()) {
            throw new BusinessException("POST_EMPTY",
                    "There is nothing written yet for the model to work from");
        }
        return text;
    }

    private String firstWords(Post post, int words) {
        String[] parts = bodyText(post).split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(parts, Math.min(words, parts.length)));
    }

    private JsonNode textNode(String content) {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("text", content);
        return mapper.valueToTree(wrapper);
    }
}