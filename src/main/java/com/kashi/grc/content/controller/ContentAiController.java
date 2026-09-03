package com.kashi.grc.content.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.service.ContentAiService;
import com.kashi.grc.content.service.ContentAiService.Proposal;
import com.kashi.grc.content.service.ContentAccessService;
import com.kashi.grc.content.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI tasks for the editor.
 *
 * ── EVERY RESPONSE IS A PROPOSAL ─────────────────────────────────────────────
 * Nothing here writes to a post. Each endpoint returns something the editor
 * renders with Accept and Reject, and only the accept path — an ordinary PUT to
 * /posts/{id} — changes the document. That is not a limitation to be relaxed
 * later; it is the reason it is safe to point a language model at content
 * published under a named author's byline.
 *
 * Every Proposal carries interactionId. The editor must post a verdict to
 * /v1/ai/feedback for accept, reject, edit AND dismiss — a suggestion shown and
 * silently abandoned is the single most informative data point this feature
 * produces, and it can only be captured at the moment it happens.
 */
@Slf4j
@RestController
@RequestMapping("/v1/content/admin/ai")
@RequiredArgsConstructor
public class ContentAiController {

    private final ContentAiService aiService;
    private final ContentAccessService accessService;
    private final PostRepository postRepository;

    /**
     * One entry point rather than eight, so the editor's AI panel is a loop over
     * a task list instead of eight bespoke handlers, and adding a ninth task is
     * a case here plus a prompt file.
     */
    @PostMapping("/{taskType}")
    public ApiResponse<Proposal> run(@PathVariable String taskType,
                                     @RequestBody Map<String, Object> body) {
        Long postId = body.get("postId") == null ? null
                : Long.valueOf(String.valueOf(body.get("postId")));

        // A task that names a post reads its body and spends the AI budget on it,
        // so require the same rights the accept path will need. CONTENT_OUTLINE
        // carries no postId — there is nothing yet to be authorised against.
        if (postId != null) {
            Post post = postRepository.findById(postId)
                    .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
            accessService.assertCanEdit(post);
        }

        Proposal proposal = switch (taskType.toUpperCase()) {
            case "CONTENT_OUTLINE" -> aiService.outline(
                    text(body, "topic"), text(body, "persona"), text(body, "targetKeyword"));
            case "CONTENT_DRAFT_SECTION" -> aiService.draftSection(
                    postId, text(body, "outline"), text(body, "heading"));
            case "CONTENT_REWRITE" -> aiService.rewrite(
                    postId, text(body, "selection"), text(body, "instruction"));
            case "CONTENT_META"           -> aiService.meta(postId);
            case "CONTENT_TLDR"           -> aiService.tldr(postId);
            case "CONTENT_FAQ"            -> aiService.faq(postId);
            case "CONTENT_INTERNAL_LINKS" -> aiService.internalLinks(postId);
            case "CONTENT_SOCIAL"         -> aiService.socialPost(postId, text(body, "channel"));
            default -> throw new BusinessException("UNKNOWN_AI_TASK",
                    "No content AI task called " + taskType);
        };

        // WARNING rather than SUCCESS where references were dropped, so the
        // editor renders the caveat instead of presenting the output as clean.
        return proposal.warnings().isEmpty()
                ? ApiResponse.success(proposal)
                : ApiResponse.warning(proposal);
    }

    private String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}