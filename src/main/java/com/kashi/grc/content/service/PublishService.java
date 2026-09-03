package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.content.domain.ContentEnums.ContentType;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.ContentMedia;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.repository.ContentMediaRepository;
import com.kashi.grc.content.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The gate between "written" and "on the internet".
 *
 * ── EVERY FAILURE AT ONCE ────────────────────────────────────────────────────
 * Validation returns the complete list, never the first problem. An editor
 * fixing one thing, clicking publish, and being told about the next thing is
 * the interaction that makes people stop using a CMS and start pasting HTML
 * into a file. Five problems means one round trip.
 *
 * ── WHAT IS ACTUALLY BLOCKING, AND WHY ───────────────────────────────────────
 * The list is short on purpose. Each item is something that cannot be fixed
 * after the fact without cost:
 *
 *   meta description   — Google writes its own if you don't, and it will pick
 *                        a sentence from the middle of the article
 *   hero image         — the OG card falls back to nothing, and the first
 *                        share on LinkedIn is the one that matters
 *   alt text           — WCAG 2.1 AA, and we sell to people who audit for it
 *   slug not redirected — publishing a URL that redirects away from itself
 *
 * Readability scores, keyword density and heading counts are deliberately NOT
 * blocking. They are advice, and advice that blocks publication stops being
 * advice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishService {

    private static final int META_DESC_MIN = 50;
    private static final int META_DESC_MAX = 160;
    private static final int META_TITLE_MAX = 60;

    private final PostRepository postRepository;
    private final ContentMediaRepository mediaRepository;
    private final ContentRedirectServiceBridge redirectBridge;
    private final BlockService blockService;

    /**
     * Publishing has consequences outside this transaction: the static site has
     * to rebuild and the tag index has to recount. Neither belongs here —
     * deciding whether a post may go live and telling Netlify about it are
     * different jobs, and an outbound HTTP call inside this transaction can
     * fire for a publish that then rolls back.
     */
    private final ApplicationEventPublisher events;

    /** Thin seam so PublishService does not depend on the whole RedirectService API. */
    @Service
    @RequiredArgsConstructor
    public static class ContentRedirectServiceBridge {
        private final RedirectService redirectService;
        void assertPublishable(String slug) { redirectService.assertSlugNotRedirected(slug); }
    }

    public record Problem(String field, String message) {}

    /**
     * @return every reason this post cannot be published. Empty means go.
     */
    public List<Problem> validate(Post post) {
        List<Problem> problems = new ArrayList<>();

        if (isBlank(post.getTitle()))  problems.add(new Problem("title", "A title is required"));
        if (isBlank(post.getSlug()))   problems.add(new Problem("slug", "A slug is required"));

        ArrayNode blocks = blockService.parse(post.getContentBlocks());
        if (blocks.isEmpty()) {
            problems.add(new Problem("contentBlocks", "Add at least one content block"));
        }

        String desc = post.getMetaDescription();
        if (isBlank(desc)) {
            problems.add(new Problem("metaDescription",
                    "A meta description is required — without one, search engines write their own"));
        } else if (desc.length() < META_DESC_MIN || desc.length() > META_DESC_MAX) {
            problems.add(new Problem("metaDescription", String.format(
                    "Meta description is %d characters; it needs to be between %d and %d",
                    desc.length(), META_DESC_MIN, META_DESC_MAX)));
        }

        if (post.getMetaTitle() != null && post.getMetaTitle().length() > META_TITLE_MAX) {
            problems.add(new Problem("metaTitle", String.format(
                    "Meta title is %d characters and will be truncated in results at about %d",
                    post.getMetaTitle().length(), META_TITLE_MAX)));
        }

        if (post.getHeroImageId() == null) {
            problems.add(new Problem("heroImageId",
                    "A hero image is required — it is also the social card fallback"));
        }

        if (post.getAuthorId() == null) {
            problems.add(new Problem("authorId", "An author is required"));
        }

        // Alt text on every referenced image. The column is NOT NULL, so this
        // catches the other case: a block pointing at media that was deleted.
        List<Long> imageIds = blockService.imageMediaIds(blocks);
        if (!imageIds.isEmpty()) {
            Map<Long, ContentMedia> found = new HashMap<>();
            mediaRepository.findByIdIn(imageIds).forEach(m -> found.put(m.getId(), m));
            for (Long id : imageIds) {
                ContentMedia m = found.get(id);
                if (m == null) {
                    problems.add(new Problem("contentBlocks",
                            "An image block points at media " + id + ", which no longer exists"));
                } else if (isBlank(m.getAltText())) {
                    problems.add(new Problem("contentBlocks",
                            "Image " + id + " has no alt text"));
                }
            }
        }

        // Type-specific requirements.
        if (post.getContentType() == ContentType.GLOSSARY && isBlank(post.getDefinitionSummary())) {
            problems.add(new Problem("definitionSummary",
                    "A glossary page needs its definition up front — that is what gets pulled into a featured snippet"));
        }
        if (post.getContentType() == ContentType.COMPARISON && isBlank(post.getComparisonDataIds())) {
            problems.add(new Problem("comparisonDataIds",
                    "A comparison page needs at least one competitor record attached"));
        }

        try {
            redirectBridge.assertPublishable(post.getSlug());
        } catch (BusinessException e) {
            problems.add(new Problem("slug", e.getMessage()));
        }

        return problems;
    }

    @Transactional
    public Post publish(Post post, Long actorId) {
        List<Problem> problems = validate(post);
        if (!problems.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("problems", problems);
            throw new BusinessException("PUBLISH_VALIDATION_FAILED",
                    problems.size() == 1
                            ? problems.get(0).message()
                            : problems.size() + " things need fixing before this can be published",
                    HttpStatus.UNPROCESSABLE_ENTITY, details);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean firstPublication = post.getPublishedAt() == null;

        // First publication sets the date. Re-publishing after an unpublish does
        // NOT — the original date is what accumulated authority, and resetting it
        // tells search engines a two-year-old guide is brand new.
        if (firstPublication) post.setPublishedAt(now);
        if (post.getContentUpdatedAt() == null) post.setContentUpdatedAt(post.getPublishedAt());
        if (post.getLastVerifiedAt() == null) post.setLastVerifiedAt(now);

        post.setStatus(PostStatus.PUBLISHED);
        post.setScheduledFor(null);
        post.setLastEditedById(actorId);

        Post saved = postRepository.save(post);
        events.publishEvent(new ContentEvents.PostPublished(
                saved.getId(), saved.getSlug(), firstPublication));

        log.info("[CONTENT-PUBLISH] published | id={} slug={} by={}", saved.getId(), saved.getSlug(), actorId);
        return saved;
    }

    @Transactional
    public Post unpublish(Post post, Long actorId) {
        post.setStatus(PostStatus.DRAFT);
        post.setLastEditedById(actorId);
        Post saved = postRepository.save(post);

        events.publishEvent(new ContentEvents.PostUnpublished(saved.getId(), saved.getSlug()));

        log.info("[CONTENT-PUBLISH] unpublished | id={} slug={}", saved.getId(), saved.getSlug());
        return saved;
    }

    @Transactional
    public Post schedule(Post post, LocalDateTime when, Long actorId) {
        if (when == null || when.isBefore(LocalDateTime.now())) {
            throw new BusinessException("SCHEDULE_IN_PAST", "Scheduled time must be in the future");
        }
        List<Problem> problems = validate(post);
        if (!problems.isEmpty()) {
            // Validate at scheduling time, not at 3am when the job runs. A post
            // that silently fails to appear is worse than one that never
            // scheduled.
            Map<String, Object> details = new HashMap<>();
            details.put("problems", problems);
            throw new BusinessException("PUBLISH_VALIDATION_FAILED",
                    "This can't be scheduled until it would pass publish validation",
                    HttpStatus.UNPROCESSABLE_ENTITY, details);
        }
        post.setStatus(PostStatus.SCHEDULED);
        post.setScheduledFor(when);
        post.setLastEditedById(actorId);
        return postRepository.save(post);
    }

    /**
     * Five minutes is deliberate. A scheduled post is a marketing decision, not
     * a market order; nobody needs second-level precision, and a tighter
     * interval means twelve times the queries for no benefit.
     */
    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void publishDueScheduledPosts() {
        List<Post> due = postRepository.findByStatusAndScheduledForLessThanEqual(
                PostStatus.SCHEDULED, LocalDateTime.now());
        if (due.isEmpty()) return;

        for (Post post : due) {
            try {
                publish(post, post.getLastEditedById());
                log.info("[CONTENT-SCHEDULE] auto-published | slug={}", post.getSlug());
            } catch (Exception e) {
                // Do not let one bad post stop the batch, and do not silently
                // leave it SCHEDULED forever — it goes back to DRAFT so it
                // shows up in the editor's list as needing attention.
                log.error("[CONTENT-SCHEDULE] could not publish {} — returning to draft: {}",
                        post.getSlug(), e.getMessage());
                post.setStatus(PostStatus.DRAFT);
                post.setScheduledFor(null);
                postRepository.save(post);
            }
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}