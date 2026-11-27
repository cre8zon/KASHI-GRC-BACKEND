package com.kashi.grc.siteassistant.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One visitor's conversation with the website assistant on www.kashiguard.com.
 *
 * ── THE TOKEN ────────────────────────────────────────────────────────────────
 * The visitor has no account. Their browser keeps a random 256-bit token and
 * sends it with every message (X-Chat-Session header); that token is the only
 * thing that can read the conversation back. Only its SHA-256 is stored, so a
 * copy of this table cannot be used to read anyone's chat.
 *
 * ── LIFECYCLE ────────────────────────────────────────────────────────────────
 *   ACTIVE       the assistant answers
 *   HANDED_OVER  the visitor asked for a person; an Enquiry exists (enquiryId)
 *                and further messages go to the team, not the AI
 *
 * Platform-owned (no tenant_id), like Enquiry: these are people who are not
 * customers yet. The visitor's IP is stored only as a salted hash.
 */
@Entity
@Table(name = "site_chat_sessions", indexes = {
        @Index(name = "ix_site_chat_session_enquiry", columnList = "enquiry_id"),
        @Index(name = "ix_site_chat_session_created", columnList = "created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_site_chat_session_token", columnNames = "token_hash"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class SiteChatSession extends BaseEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String HANDED_OVER = "HANDED_OVER";

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** ACTIVE | HANDED_OVER */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = ACTIVE;

    /** Set at handover: the Enquiry the team works. */
    @Column(name = "enquiry_id")
    private Long enquiryId;

    // ── where the visitor came from ──────────────────────────────────────────
    @Column(name = "first_page", length = 512)
    private String firstPage;

    @Column(name = "last_page", length = 512)
    private String lastPage;

    @Column(name = "landing_page", length = 512)
    private String landingPage;

    @Column(name = "referrer", length = 512)
    private String referrer;

    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "utm_medium", length = 100)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 150)
    private String utmCampaign;

    @Column(name = "utm_term", length = 150)
    private String utmTerm;

    @Column(name = "utm_content", length = 150)
    private String utmContent;

    // ── counters (rate limits and the handover rule) ─────────────────────────
    @Column(name = "visitor_messages", nullable = false)
    @Builder.Default
    private Integer visitorMessages = 0;

    /** Consecutive answers the assistant was not confident in. Two → offer a person. */
    @Column(name = "unanswered_streak", nullable = false)
    @Builder.Default
    private Integer unansweredStreak = 0;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    // ── handover ─────────────────────────────────────────────────────────────
    /** The AI's summary for the team, written at handover. */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "topic", length = 100)
    private String topic;

    @Column(name = "handed_over_at")
    private LocalDateTime handedOverAt;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "user_agent", length = 255)
    private String userAgent;
}
