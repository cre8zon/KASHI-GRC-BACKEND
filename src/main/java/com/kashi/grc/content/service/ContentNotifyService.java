package com.kashi.grc.content.service;

import com.kashi.grc.common.service.MailService;
import com.kashi.grc.content.service.ContentEvents.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything that happens after content is committed: the static rebuild, the
 * tag recount, and the one email this module sends.
 *
 * ── AFTER_COMMIT, ALWAYS ─────────────────────────────────────────────────────
 * Every listener binds to AFTER_COMMIT. A build hook fired inside the publish
 * transaction can trigger a rebuild for a publish that then rolls back, and the
 * site would render from a database state that never existed. Rare, confusing,
 * and completely avoidable.
 *
 * ── THE BUILD HOOK IS DEBOUNCED ──────────────────────────────────────────────
 * Publishing five queued articles in one sitting should produce one rebuild,
 * not five. Netlify and Vercel both queue and run every hook call, so an
 * undebounced hook turns a morning of publishing into five full site builds —
 * slow, and on a metered plan, expensive. A short quiet window collapses a
 * burst into one build.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentNotifyService {

    private final ContentTaxonomyService taxonomyService;
    private final MailService mailService;

    @Value("${content.publish.build-hook-url:}")
    private String buildHookUrl;

    @Value("${content.site.base-url:https://www.digiosec.com}")
    private String siteBaseUrl;

    /** Collapse a burst of publishes into one build. */
    @Value("${content.publish.build-hook-debounce-seconds:90}")
    private long debounceSeconds;

    private final AtomicLong lastHookAt = new AtomicLong(0);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ── publish ──────────────────────────────────────────────────────────────

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPublished(PostPublished event) {
        // A newly published post may push a tag over the indexable threshold,
        // and the sitemap the build is about to read depends on that. Recount
        // before triggering the rebuild, not after.
        try {
            taxonomyService.refreshTagIndexability();
        } catch (Exception e) {
            log.warn("[CONTENT-NOTIFY] tag recount failed: {}", e.getMessage());
        }
        triggerBuild("published " + event.slug());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnpublished(PostUnpublished event) {
        // Unpublishing is more urgent than publishing: until the rebuild lands,
        // the withdrawn article is still a static file being served.
        triggerBuild("unpublished " + event.slug());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUrlChanged(PostUrlChanged event) {
        // The redirect row exists but is only enforced once the build compiles
        // it into host rules. Until then the old URL 404s.
        triggerBuild("slug " + event.oldSlug() + " -> " + event.newSlug());
    }

    // ── newsletter ───────────────────────────────────────────────────────────

    /**
     * The confirmation email. Nothing else is ever sent to an address that has
     * not clicked through this — see NewsletterSubscriber for why that is not
     * negotiable for a company selling compliance software.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionRequested(SubscriberConfirmationRequested event) {
        try {
            mailService.send("CONTENT_NEWSLETTER_CONFIRM", event.email(), Map.of(
                    "firstName", event.name() == null ? "there" : event.name(),
                    "confirmUrl", siteBaseUrl + "/newsletter/confirm?token=" + event.token()
            ));
        } catch (Exception e) {
            // A failed send leaves the subscriber unconfirmed, which is the
            // correct end state — they are simply not on the list. Better than
            // a half-subscribed record with no consent behind it.
            log.error("[CONTENT-NOTIFY] confirmation email failed: {}", e.getMessage());
        }
    }

    // ── the hook ─────────────────────────────────────────────────────────────

    private void triggerBuild(String reason) {
        if (buildHookUrl == null || buildHookUrl.isBlank()) {
            log.info("[CONTENT-NOTIFY] no build hook configured; {} will appear at the next deploy", reason);
            return;
        }

        long now = System.currentTimeMillis();
        long previous = lastHookAt.get();
        if (now - previous < debounceSeconds * 1000) {
            log.info("[CONTENT-NOTIFY] build already queued within the last {}s, skipping ({})",
                    debounceSeconds, reason);
            return;
        }
        if (!lastHookAt.compareAndSet(previous, now)) return;   // another thread won

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(buildHookUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            log.info("[CONTENT-NOTIFY] build hook fired | reason={} status={}", reason, status);
        } catch (Exception e) {
            // Not fatal and not retried. The post is published either way; the
            // worst case is that it appears at the next deploy, and the SPA
            // fallback means its URL already works in the meantime.
            log.error("[CONTENT-NOTIFY] build hook failed ({}): {}", reason, e.getMessage());
        }
    }
}