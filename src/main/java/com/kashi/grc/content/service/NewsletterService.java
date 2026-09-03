package com.kashi.grc.content.service;

import com.kashi.grc.content.domain.NewsletterSubscriber;
import com.kashi.grc.content.repository.NewsletterSubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * The newsletter list.
 *
 * ── DOUBLE OPT-IN, AND WHY IT IS NOT NEGOTIABLE HERE ─────────────────────────
 * We sell compliance software to people who read privacy law for a living. A
 * newsletter that adds an address on submit — no confirmation, no verifiable
 * consent record — is a DPDP and GDPR problem being run by the company whose
 * product is not having those. The first person to notice will be a prospect.
 *
 * confirmedAt IS the consent record. Nothing is sent before it is set, so an
 * unwired mail sender fails closed rather than open.
 *
 * ── THE ENDPOINT ANSWERS THE SAME WAY EVERY TIME ─────────────────────────────
 * Subscribing an address that is already confirmed returns the same message as
 * subscribing a new one. Otherwise the public endpoint becomes an oracle for
 * "is this person on your list", which is a disclosure we have no business
 * making about our own readers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsletterService {

    private final NewsletterSubscriberRepository repository;

    /**
     * The confirmation email is not sent from here.
     *
     * This method runs inside a transaction. Sending mail inside one means a
     * rollback after the send leaves someone holding a confirmation link for a
     * subscriber row that does not exist. So it publishes an event, and
     * ContentNotifyService sends AFTER_COMMIT through the platform's existing
     * MailService and the CONTENT_NEWSLETTER_CONFIRM template.
     */
    private final ApplicationEventPublisher events;

    @Transactional
    public void subscribe(String rawEmail, String name, String sourcePath) {
        String email = normalise(rawEmail);
        if (email == null) return;   // silently — see the class comment

        NewsletterSubscriber sub = repository.findByEmail(email)
                .orElseGet(() -> NewsletterSubscriber.builder().email(email).build());

        if (sub.getConfirmedAt() != null && sub.getUnsubscribedAt() == null) return;

        String token = randomToken();
        sub.setConfirmToken(token);
        sub.setName(name);
        sub.setSourcePath(sourcePath);
        sub.setUnsubscribedAt(null);
        repository.save(sub);

        events.publishEvent(new ContentEvents.SubscriberConfirmationRequested(email, name, token));
    }

    @Transactional
    public boolean confirm(String token) {
        return repository.findByConfirmToken(token).map(sub -> {
            sub.setConfirmedAt(LocalDateTime.now());
            sub.setConfirmToken(null);     // single use
            repository.save(sub);
            log.info("[CONTENT-NEWSLETTER] confirmed | source={}", sub.getSourcePath());
            return true;
        }).orElse(false);
    }

    /**
     * One click, no login, no confirmation step.
     *
     * The asymmetry with subscribing is the point: consent has to be proven,
     * withdrawal has to be easy. An unsubscribe that asks someone to log in is
     * how a list becomes a complaint.
     */
    @Transactional
    public void unsubscribe(String rawEmail) {
        String email = normalise(rawEmail);
        if (email == null) return;
        repository.findByEmail(email).ifPresent(sub -> {
            sub.setUnsubscribedAt(LocalDateTime.now());
            repository.save(sub);
        });
    }

    public Map<String, Object> stats() {
        return Map.of("confirmed", repository.countByConfirmedAtIsNotNullAndUnsubscribedAtIsNull());
    }

    private String normalise(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase();
        // Not a validation library. Enough to reject obvious junk without
        // rejecting the long tail of legitimate addresses, which is a losing
        // game — the confirmation email is the real validator.
        if (e.length() < 5 || e.length() > 320 || !e.contains("@") || e.contains(" ")) return null;
        return e;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}