package com.kashi.grc.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Database-stored email template.
 * Templates are looked up by name (e.g. "user-invitation", "password-reset").
 * Content may contain {{placeholder}} tokens replaced by MailService at send time.
 */
@Entity
@Table(name = "emailtemplate")
@Getter
@Setter
public class EmailTemplate extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String subject;

    /** HTML or plain-text body. Use {{key}} placeholders. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** "text/html" or "text/plain" — defaults to text/html */
    @Column
    private String mimeType = "text/html";

    @Column
    private boolean isActive = true;
}
