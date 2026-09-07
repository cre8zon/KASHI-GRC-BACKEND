package com.kashi.grc.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tells the static site to rebuild.
 *
 * ── WHY THIS EXISTS AT ALL ───────────────────────────────────────────────────
 * The public site is prerendered. Publishing writes a row here; nothing on
 * www.digiosec.com changes until a build runs. The SPA fallback means the URL
 * works immediately — it just is not static, so it is invisible to the crawlers
 * that do not execute JavaScript, which are the ones this whole architecture
 * exists to serve.
 *
 * ── AFTER COMMIT, NOT DURING ─────────────────────────────────────────────────
 * @TransactionalEventListener(AFTER_COMMIT). Firing inside the transaction
 * would occasionally trigger a build for a publish that then rolled back, and
 * the site would serve an article the database does not have.
 *
 * ── DEBOUNCED ────────────────────────────────────────────────────────────────
 * Publishing five posts in a row should produce one build, not five. Netlify
 * bills build minutes and each one takes a couple of minutes anyway, so the
 * fourth request would queue behind work that is already going to include it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentBuildHookService {

    /** Netlify/Vercel build hook. Unset means no builds — logged, not thrown. */
    @Value("${content.publish.build-hook-url:}")
    private String buildHookUrl;

    /** Two publishes inside this window share one build. */
    private static final Duration DEBOUNCE = Duration.ofSeconds(90);

    private final AtomicReference<Instant> lastTriggered = new AtomicReference<>(Instant.EPOCH);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Published by PublishService after publish, unpublish, archive or a slug change. */
    public record SiteChanged(String reason, String slug) {}

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSiteChanged(SiteChanged event) {
        if (buildHookUrl == null || buildHookUrl.isBlank()) {
            log.info("[CONTENT-BUILD] no build hook configured — {} ({}) will appear at the next deploy",
                    event.reason(), event.slug());
            return;
        }

        Instant now = Instant.now();
        Instant previous = lastTriggered.get();
        if (previous.plus(DEBOUNCE).isAfter(now)) {
            log.info("[CONTENT-BUILD] within debounce window — {} folded into the pending build", event.slug());
            return;
        }
        if (!lastTriggered.compareAndSet(previous, now)) return;   // another thread won

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(buildHookUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"trigger_title\":\"content: " + event.reason() + "\"}"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            log.info("[CONTENT-BUILD] triggered | reason={} slug={} status={}",
                    event.reason(), event.slug(), res.statusCode());
        } catch (Exception e) {
            // A failed build hook must never fail a publish. The post is live in
            // the API either way, and the next publish or scheduled deploy
            // picks it up.
            log.error("[CONTENT-BUILD] hook failed for {}: {}", event.slug(), e.getMessage());
            lastTriggered.set(previous);   // let the next event retry immediately
        }
    }
}
