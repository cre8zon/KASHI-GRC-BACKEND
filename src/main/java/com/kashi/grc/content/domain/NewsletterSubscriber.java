package com.kashi.grc.content.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Someone who asked to hear from us.
 *
 * ── DOUBLE OPT-IN IS NOT OPTIONAL HERE ───────────────────────────────────────
 * We sell compliance software to people who read privacy law for a living. A
 * newsletter that adds an address on submit, with no confirmation and no
 * verifiable consent record, is a DPDP and GDPR problem being run by the
 * company whose product is not having those. confirmedAt is the consent record;
 * nothing is sent before it is set.
 *
 * sourcePath records which article earned the signup, which is the only way to
 * know whether the newsletter block is worth its space.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "content_newsletter_subscribers", indexes = {
        @Index(name = "ix_subscriber_email", columnList = "email", unique = true),
        @Index(name = "ix_subscriber_token", columnList = "confirm_token")
})
public class NewsletterSubscriber extends BaseEntity {

    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "name", length = 255)
    private String name;

    /** Single-use, unguessable. Cleared once confirmed. */
    @Column(name = "confirm_token", length = 64)
    private String confirmToken;

    /** The consent record. Null means never send. */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "unsubscribed_at")
    private LocalDateTime unsubscribedAt;

    /** Which page the signup came from. */
    @Column(name = "source_path", length = 512)
    private String sourcePath;
}
