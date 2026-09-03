package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.kashi.grc.content.domain.ContentEnums.ContentType;
import com.kashi.grc.content.domain.ContentEnums.LinkHealth;
import com.kashi.grc.content.domain.ContentEnums.LinkKind;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.ContentEnums.RobotsDirective;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.domain.PostLink;
import com.kashi.grc.content.repository.PostLinkRepository;
import com.kashi.grc.content.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * The link graph, and the two reports that read it.
 *
 * ── ORPHAN PAGES ─────────────────────────────────────────────────────────────
 * A published article with no inbound internal links is in the worst state a
 * piece of content can be in: it exists, it is in the sitemap, it consumed a
 * week of someone's time, and no page on the site points at it. Search engines
 * infer importance partly from internal links, so an orphan is telling them the
 * site itself does not think the page matters.
 *
 * Content teams find these with a spreadsheet, once, and then stop. Here it is
 * one indexed query, because the graph is materialised on every save — and the
 * blocks are already being parsed on save anyway for the read time, so the
 * extraction is close to free.
 *
 * ── BROKEN LINKS ─────────────────────────────────────────────────────────────
 * External links rot silently. A compliance article citing an RBI circular that
 * has moved is worse than one with no citation, because the citation was the
 * thing establishing that we checked. The weekly sweep is deliberately gentle:
 * HEAD requests, one pass, failures recorded rather than retried hard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentInsightsService {

    private final PostLinkRepository linkRepository;
    private final PostRepository postRepository;
    private final BlockService blockService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)   // we want to SEE the 301
            .build();

    // ── graph maintenance ────────────────────────────────────────────────────

    /**
     * Rebuild this post's outgoing links, then recompute inbound counts.
     *
     * Delete-and-reinsert rather than diffing. The row count per post is small,
     * the operation runs only when blocks actually changed, and a diff here
     * would be more code defending against a problem that does not exist.
     */
    @Transactional
    public void reindexLinks(Post post) {
        // Which posts had an inbound edge from this one BEFORE the rewrite.
        // Their counts change too when a link is removed, and they are the half
        // of the problem that is easy to forget.
        Set<Long> touched = new HashSet<>();
        linkRepository.findByFromPostId(post.getId())
                .forEach(l -> { if (l.getToPostId() != null) touched.add(l.getToPostId()); });

        linkRepository.deleteByFromPostId(post.getId());

        ArrayNode blocks = blockService.parse(post.getContentBlocks());
        List<PostLink> rows = new ArrayList<>();

        for (BlockService.ExtractedLink link : blockService.links(blocks)) {
            String href = link.href();
            if (href == null || href.isBlank() || href.startsWith("#")
                    || href.startsWith("mailto:") || href.startsWith("tel:")) continue;

            LinkKind kind;
            Long toPostId = null;

            if (href.startsWith("/blog/")) {
                String slug = href.substring("/blog/".length()).split("[#?]")[0];
                toPostId = postRepository.findBySlug(slug).map(Post::getId).orElse(null);
                // A /blog/ link that resolves to nothing is broken on arrival —
                // record it as such rather than as a generic internal page.
                kind = toPostId != null ? LinkKind.INTERNAL_POST : LinkKind.INTERNAL_PAGE;
            } else if (href.startsWith("/")) {
                kind = LinkKind.INTERNAL_PAGE;
            } else {
                kind = LinkKind.EXTERNAL;
            }

            rows.add(PostLink.builder()
                    .fromPostId(post.getId())
                    .toPostId(toPostId)
                    .href(href.length() > 1024 ? href.substring(0, 1024) : href)
                    .anchorText(truncate(link.anchorText(), 512))
                    .linkKind(kind)
                    .health(LinkHealth.UNCHECKED)
                    .build());
        }

        linkRepository.saveAll(rows);

        rows.forEach(l -> { if (l.getToPostId() != null) touched.add(l.getToPostId()); });
        recountInbound(touched);
    }

    @Transactional
    public void clearLinks(Long postId) {
        Set<Long> touched = new HashSet<>();
        linkRepository.findByFromPostId(postId)
                .forEach(l -> { if (l.getToPostId() != null) touched.add(l.getToPostId()); });

        linkRepository.deleteByFromPostId(postId);
        recountInbound(touched);
    }

    /**
     * Recount only the posts whose inbound edges actually changed.
     *
     * The first version of this called recomputeInboundCounts() on every save,
     * which loads every post in the library to update at most a handful of
     * counters. On an empty database that is invisible; at a few hundred
     * articles it is the slowest thing in an autosave, and autosave fires every
     * two seconds while someone types.
     *
     * A save touches the posts this one used to link to plus the ones it links
     * to now — usually zero to five rows.
     */
    @Transactional
    public void recountInbound(Set<Long> postIds) {
        if (postIds.isEmpty()) return;
        for (Long id : postIds) {
            int count = linkRepository.findByToPostId(id).size();
            postRepository.setInboundLinkCount(id, count);
        }
    }

    /**
     * Whole-table recompute. Not on the save path — see recountInbound.
     *
     * Incremental counters do drift: a post archived here, a link edited there,
     * and six months later the orphan report is quietly wrong in the direction
     * that hides orphans. So this still exists and runs nightly, where its cost
     * does not land on someone's keystroke.
     */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void recomputeInboundCounts() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : linkRepository.countInboundByPost()) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        for (Post post : postRepository.findAll()) {
            int expected = counts.getOrDefault(post.getId(), 0);
            if (!Integer.valueOf(expected).equals(post.getInboundLinkCount())) {
                postRepository.setInboundLinkCount(post.getId(), expected);
            }
        }
    }

    // ── reports ──────────────────────────────────────────────────────────────

    public List<Post> orphanPages() {
        return postRepository.findOrphans(PostStatus.PUBLISHED, RobotsDirective.INDEX_FOLLOW);
    }

    /**
     * Posts past their review interval.
     *
     * The query uses a conservative cutoff and the exact interval is applied
     * here, because reviewIntervalMonths varies per post and pushing that into
     * SQL would mean date arithmetic against a per-row column for no gain on a
     * report that runs weekly.
     */
    public List<Post> staleContent() {
        LocalDateTime widest = LocalDateTime.now().minusMonths(1);
        List<Post> candidates = postRepository.findPossiblyStale(
                PostStatus.PUBLISHED, ContentType.COMPARISON, widest);
        List<Post> stale = new ArrayList<>();
        for (Post p : candidates) {
            LocalDateTime due = (p.getLastVerifiedAt() == null ? p.getPublishedAt() : p.getLastVerifiedAt())
                    .plusMonths(p.getReviewIntervalMonths());
            if (due.isBefore(LocalDateTime.now())) stale.add(p);
        }
        return stale;
    }

    public List<PostLink> brokenLinks() {
        return linkRepository.findByHealth(LinkHealth.BROKEN);
    }

    // ── the sweep ────────────────────────────────────────────────────────────

    /**
     * Weekly, not nightly. External sites do not appreciate being probed daily,
     * and a link that broke on Tuesday being noticed on Sunday costs nothing.
     */
    @Scheduled(cron = "0 30 3 * * SUN")
    @Transactional
    public void checkLinks() {
        List<PostLink> checkable = linkRepository.findCheckable(LinkKind.INTERNAL_POST);
        log.info("[CONTENT-LINKS] checking {} links", checkable.size());

        for (PostLink link : checkable) {
            try {
                if (link.getLinkKind() == LinkKind.INTERNAL_PAGE && link.getHref().startsWith("/blog/")) {
                    // An internal /blog/ link that never resolved to a post.
                    link.setHealth(LinkHealth.BROKEN);
                    link.setHttpStatus(404);
                } else if (link.getLinkKind() == LinkKind.EXTERNAL) {
                    int status = headStatus(link.getHref());
                    link.setHttpStatus(status);
                    link.setHealth(
                            status >= 200 && status < 300 ? LinkHealth.OK
                                    : status >= 300 && status < 400 ? LinkHealth.REDIRECTED
                                      : LinkHealth.BROKEN);
                } else {
                    link.setHealth(LinkHealth.OK);
                }
            } catch (Exception e) {
                // A timeout is not proof of a broken link — a slow server, a
                // rate limit or our own network can all produce one, and
                // flagging those would fill the report with noise nobody acts
                // on. Left UNCHECKED to be retried next week.
                log.debug("[CONTENT-LINKS] could not check {}: {}", link.getHref(), e.getMessage());
            }
            link.setLastCheckedAt(LocalDateTime.now());
        }
        linkRepository.saveAll(checkable);
    }

    private int headStatus(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "KashiGRC-LinkChecker/1.0 (+https://www.digiosec.com)")
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}