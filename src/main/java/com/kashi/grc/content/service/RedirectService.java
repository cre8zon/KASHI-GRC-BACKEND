package com.kashi.grc.content.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.content.domain.ContentRedirect;
import com.kashi.grc.content.repository.ContentRedirectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 301s. The rule is short and absolute: a published URL never 404s.
 *
 * ── WHY THIS IS AUTOMATIC AND NOT A PROMPT ───────────────────────────────────
 * The moment a slug changes on a published post, a redirect is written. Not
 * suggested in a dialog an editor can dismiss — written, in the same
 * transaction, before the new slug is saved. Editors rename articles for good
 * reasons and will not reliably remember what six months of inbound links are
 * worth.
 *
 * ── CHAINS AND LOOPS ─────────────────────────────────────────────────────────
 * Two failure modes, both silent, both caught here:
 *
 *   loop   A -> B -> A. Browsers give up; crawlers drop the URL.
 *   chain  A -> B -> C. Each hop leaks authority and adds latency. When B is
 *          about to point at C, every redirect that pointed at B is repointed
 *          at C, so the chain is flattened as it forms rather than swept up
 *          later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectService {

    private final ContentRedirectRepository repository;

    /**
     * Record a slug change. Called by PostService before the new slug is saved.
     * A no-op when the post was never published — there is nothing out there
     * pointing at a draft's URL.
     */
    @Transactional
    public void recordSlugChange(Long postId, String oldSlug, String newSlug, Long actorId) {
        if (oldSlug == null || oldSlug.equals(newSlug)) return;

        String from = "/blog/" + oldSlug;
        String to   = "/blog/" + newSlug;

        assertNoLoop(from, to);

        // If anything already redirects TO the old path, repoint it at the new
        // one now. Otherwise the next visitor takes two hops.
        List<ContentRedirect> incoming = repository.findByToPath(from);
        for (ContentRedirect r : incoming) {
            if (r.getFromPath().equals(to)) continue;   // would create a loop
            r.setToPath(to);
            log.info("[CONTENT-REDIRECT] flattened chain | {} -> {} (was {})",
                    r.getFromPath(), to, from);
        }
        repository.saveAll(incoming);

        Optional<ContentRedirect> existing = repository.findByFromPath(from);
        ContentRedirect redirect = existing.orElseGet(() ->
                ContentRedirect.builder().fromPath(from).build());
        redirect.setToPath(to);
        redirect.setStatusCode(301);
        redirect.setActive(true);
        redirect.setPostId(postId);
        redirect.setCreatedById(actorId);
        redirect.setReason("slug changed from " + oldSlug + " to " + newSlug);
        repository.save(redirect);

        log.info("[CONTENT-REDIRECT] {} -> {} | post={}", from, to, postId);
    }

    /**
     * Manual creation from the admin.
     *
     * Rejects rather than warns when the source is a live post's URL: creating
     * a redirect away from a page that still exists takes it out of the index
     * and is almost never what someone meant.
     */
    @Transactional
    public ContentRedirect create(String fromPath, String toPath, Integer statusCode,
                                  String reason, Long actorId) {
        String from = normalise(fromPath);
        String to   = normalise(toPath);

        if (from.equals(to)) {
            throw new BusinessException("REDIRECT_SELF", "A path cannot redirect to itself");
        }
        assertNoLoop(from, to);

        if (repository.findByFromPath(from).isPresent()) {
            throw new BusinessException("REDIRECT_EXISTS",
                    "A redirect from " + from + " already exists. Edit it instead.");
        }

        return repository.save(ContentRedirect.builder()
                .fromPath(from)
                .toPath(to)
                .statusCode(statusCode == null ? 301 : statusCode)
                .reason(reason)
                .createdById(actorId)
                .active(true)
                .build());
    }

    /**
     * Called before publishing a slug. If the slug being published is the
     * SOURCE of an existing redirect, that URL is about to both exist and
     * redirect away from itself.
     */
    public void assertSlugNotRedirected(String slug) {
        repository.findByFromPath("/blog/" + slug)
                .filter(ContentRedirect::getActive)
                .ifPresent(r -> {
                    throw new BusinessException("SLUG_IS_REDIRECT_SOURCE",
                            "/blog/" + slug + " currently redirects to " + r.getToPath()
                            + ". Deactivate that redirect before publishing this slug.");
                });
    }

    /** Walk forward from `to`; if we arrive back at `from`, it is a loop. */
    private void assertNoLoop(String from, String to) {
        Set<String> seen = new HashSet<>();
        seen.add(from);
        String cursor = to;
        for (int hops = 0; hops < 25; hops++) {
            if (!seen.add(cursor)) {
                throw new BusinessException("REDIRECT_LOOP",
                        "That redirect would create a loop: " + from + " -> " + to);
            }
            Optional<ContentRedirect> next = repository.findByFromPath(cursor);
            if (next.isEmpty() || !next.get().getActive()) return;
            cursor = next.get().getToPath();
        }
        throw new BusinessException("REDIRECT_CHAIN_TOO_LONG",
                "That redirect chain is longer than 25 hops");
    }

    public List<ContentRedirect> allActive() {
        return repository.findByActiveTrue();
    }

    private String normalise(String path) {
        if (path == null || path.isBlank()) {
            throw new BusinessException("REDIRECT_PATH_REQUIRED", "Both paths are required");
        }
        String p = path.trim();
        if (!p.startsWith("/") && !p.startsWith("http")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
