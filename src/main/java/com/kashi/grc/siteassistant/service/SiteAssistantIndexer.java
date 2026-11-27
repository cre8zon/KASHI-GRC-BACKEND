package com.kashi.grc.siteassistant.service;

import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.rag.IngestionAsyncFacade;
import com.kashi.grc.ai.rag.IngestionService;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.repository.PostRepository;
import com.kashi.grc.content.service.BlockService;
import com.kashi.grc.content.service.ContentEvents;
import com.kashi.grc.siteassistant.domain.SiteChatSourcePage;
import com.kashi.grc.siteassistant.dto.SiteChatDtos.SyncPage;
import com.kashi.grc.siteassistant.dto.SiteChatDtos.SyncRequest;
import com.kashi.grc.siteassistant.repository.SiteChatSourcePageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Keeps the website assistant's corpus in step with the website.
 *
 *   Pages (product, pricing, frameworks, security…): pushed by the website's
 *     own tool after each deploy — tools/sync-assistant.mjs renders every
 *     built page and posts its text here. A page whose text has not changed
 *     is skipped; a full sync retires pages no longer on the site.
 *   Posts (blog, glossary, resources): indexed from the content platform the
 *     moment they are published, and removed when unpublished.
 *
 * Everything is platform-wide (tenantId null) under WEBSITE_PAGE /
 * WEBSITE_POST. Embedding runs on the AI executor (IngestionAsyncFacade), so
 * neither a deploy nor a publish waits for it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteAssistantIndexer {

    private static final int MAX_TEXT = 60_000;
    private static final int MAX_PAGES = 1_000;

    private final SiteChatSourcePageRepository pageRepository;
    private final PostRepository               postRepository;
    private final BlockService                 blockService;
    private final IngestionAsyncFacade         ingestion;
    private final IngestionService             ingestionService;

    // ── pages ────────────────────────────────────────────────────────────────

    public Map<String, Object> syncPages(SyncRequest req) {
        List<SyncPage> pages = req == null || req.pages() == null ? List.of() : req.pages();
        if (pages.size() > MAX_PAGES) pages = pages.subList(0, MAX_PAGES);
        String batch = "site-sync-" + System.currentTimeMillis();
        int indexed = 0, unchanged = 0, skipped = 0;
        Set<String> seen = new HashSet<>();

        for (SyncPage p : pages) {
            String path = normalisePath(p.path());
            String text = p.text() == null ? "" : p.text().trim();
            if (path == null || text.length() < 40 || !seen.add(path)) { skipped++; continue; }
            if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
            String title = p.title() == null || p.title().isBlank() ? path : trim(p.title(), 300);
            String hash = IngestionService.sha256(title + "\n" + text);

            SiteChatSourcePage row = pageRepository.findByPath(path).orElse(null);
            if (row != null && hash.equals(row.getContentHash()) && Boolean.TRUE.equals(row.getActive())) {
                row.setSyncedAt(LocalDateTime.now());
                pageRepository.save(row);
                unchanged++;
                continue;
            }
            if (row == null) row = SiteChatSourcePage.builder().path(path).build();
            row.setTitle(title);
            row.setContentHash(hash);
            row.setActive(true);
            row.setSyncedAt(LocalDateTime.now());
            row = pageRepository.save(row);

            ingestion.ingest(new IngestionService.IngestRequest(
                    ChunkSourceType.WEBSITE_PAGE, row.getId(), title, title + "\n\n" + text, false,
                    null, null, batch, Map.of("path", path)));
            indexed++;
        }

        int retired = 0;
        if (Boolean.TRUE.equals(req == null ? null : req.complete())) {
            for (SiteChatSourcePage row : pageRepository.findActive()) {
                if (seen.contains(row.getPath())) continue;
                row.setActive(false);
                row.setContentHash(null);           // so the page is re-indexed if it comes back
                pageRepository.save(row);
                ingestionService.purge(ChunkSourceType.WEBSITE_PAGE, row.getId());
                retired++;
            }
        }
        log.info("[SITE-ASSISTANT] page sync | received={} indexed={} unchanged={} retired={} skipped={}",
                pages.size(), indexed, unchanged, retired, skipped);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("received", pages.size());
        m.put("indexed", indexed);
        m.put("unchanged", unchanged);
        m.put("retired", retired);
        m.put("skipped", skipped);
        return m;
    }

    public List<Map<String, Object>> pages() {
        return pageRepository.findActive().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("path", p.getPath());
            m.put("title", p.getTitle());
            m.put("syncedAt", p.getSyncedAt());
            return m;
        }).toList();
    }

    // ── posts ────────────────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostPublished(ContentEvents.PostPublished ev) {
        try {
            postRepository.findById(ev.postId()).ifPresent(this::indexPost);
        } catch (Exception ex) {
            log.warn("[SITE-ASSISTANT] could not index post {}: {}", ev.postId(), ex.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostUnpublished(ContentEvents.PostUnpublished ev) {
        try {
            ingestionService.purge(ChunkSourceType.WEBSITE_POST, ev.postId());
        } catch (Exception ex) {
            log.warn("[SITE-ASSISTANT] could not remove post {}: {}", ev.postId(), ex.getMessage());
        }
    }

    /** Every published post, e.g. the first time the assistant is switched on. */
    public int reindexAllPosts() {
        List<Post> posts = postRepository.findByStatusOrderByPublishedAtDesc(PostStatus.PUBLISHED);
        int n = 0;
        for (Post p : posts) if (indexPost(p)) n++;
        log.info("[SITE-ASSISTANT] queued {} published posts for indexing", n);
        return n;
    }

    private boolean indexPost(Post p) {
        if (p.getStatus() != PostStatus.PUBLISHED) return false;
        String body;
        try {
            body = blockService.textOf(blockService.parse(p.getContentBlocks()));
        } catch (Exception ex) {
            body = "";
        }
        StringBuilder text = new StringBuilder(p.getTitle() == null ? "" : p.getTitle());
        if (p.getExcerpt() != null && !p.getExcerpt().isBlank()) text.append("\n\n").append(p.getExcerpt().trim());
        if (body != null && !body.isBlank()) text.append("\n\n").append(body.trim());
        if (text.length() < 40) return false;
        String t = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text.toString();
        ingestion.ingest(new IngestionService.IngestRequest(
                ChunkSourceType.WEBSITE_POST, p.getId(), trim(p.getTitle(), 300), t, false,
                null, null, "site-post-" + p.getId(), Map.of("slug", String.valueOf(p.getSlug()))));
        return true;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** "/pricing/" → "/pricing"; absolute URLs, query strings and fragments are reduced to the path. */
    static String normalisePath(String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        if (p.isEmpty()) return null;
        p = p.replaceFirst("^https?://[^/]+", "");
        int cut = p.indexOf('?');
        if (cut >= 0) p = p.substring(0, cut);
        cut = p.indexOf('#');
        if (cut >= 0) p = p.substring(0, cut);
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1) p = p.replaceAll("/+$", "");
        if (p.length() > 400 || p.contains("..")) return null;
        return p;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
