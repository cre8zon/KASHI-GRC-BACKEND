package com.kashi.grc.siteassistant.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request and response shapes for the website assistant. Records, so the
 * public API's contract is exactly these fields and nothing an entity grows
 * later leaks out to the open internet.
 */
public final class SiteChatDtos {

    private SiteChatDtos() {}

    // ── public (www.kashiguard.com) ──────────────────────────────────────────

    /** A visitor's message. {@code website} is the honeypot: real visitors leave it empty. */
    public record AskRequest(String message, String page, String landingPage, String referrer,
                             String utmSource, String utmMedium, String utmCampaign,
                             String utmTerm, String utmContent, String website) {}

    /** A page the answer drew on. */
    public record Citation(String label, String title, String url) {}

    /**
     * One message as the visitor sees it. {@code authorName} is set on STAFF
     * replies only (a first name — "Priya, KashiGuard team").
     */
    public record MessageView(Long id, String role, String body, List<Citation> citations,
                              Boolean handoverSuggested, String authorName, LocalDateTime at) {}

    /**
     * The reply to a message.
     *   sessionToken  only on the first message of a new chat — the browser keeps it
     *   status        ACTIVE | HANDED_OVER
     *   reference     the enquiry reference once handed over
     *   reply         the assistant's answer (null when the message went to the team)
     *   handover      suggest / type / reason — the widget offers "Talk to a person"
     *   notice        a short line the widget shows instead of, or under, an answer
     */
    public record AskResponse(String sessionToken, String status, String reference, MessageView reply,
                              Handover handover, String notice) {}

    public record Handover(boolean suggest, String type, String reason) {}

    /** The visitor asking for a person. privacyAccepted must be true. */
    public record HandoverRequest(String fullName, String email, String company, String type, String note,
                                  Boolean marketingConsent, Boolean privacyAccepted, String page, String website) {}

    public record HandoverResponse(String status, String reference, String message) {}

    /** The conversation, for a returning visitor (it shows the team's replies). */
    public record SessionView(String status, String reference, List<MessageView> messages) {}

    // ── admin (Enquiries inbox) ──────────────────────────────────────────────

    public record AdminChatView(Long sessionId, String status, String summary, String topic,
                                LocalDateTime startedAt, LocalDateTime handedOverAt,
                                String firstPage, String lastPage, List<MessageView> messages) {}

    public record ReplyRequest(String body) {}

    /** A question the assistant could not answer: a gap in the website's content. */
    public record UnansweredItem(Long messageId, String question, String answer, String page,
                                 LocalDateTime at, Long sessionId, Long enquiryId) {}

    // ── website sync tool ────────────────────────────────────────────────────

    public record SyncPage(String path, String title, String text) {}

    /** complete=true: this is every page; pages not listed are removed from the assistant. */
    public record SyncRequest(List<SyncPage> pages, Boolean complete) {}
}
