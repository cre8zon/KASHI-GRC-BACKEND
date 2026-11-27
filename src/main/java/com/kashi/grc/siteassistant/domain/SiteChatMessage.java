package com.kashi.grc.siteassistant.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One message in a website chat. Append-only.
 *
 *   VISITOR    what the visitor typed
 *   ASSISTANT  the AI's answer (citations, confidence, handover hint)
 *   STAFF      a reply from the KashiGuard team, written in the Enquiries
 *              inbox; also sent to the visitor by email
 *
 * An ASSISTANT row points at the VISITOR message it answered (inReplyTo), so
 * "questions the assistant could not answer" is a direct read: unconfident
 * answers, joined to their questions.
 */
@Entity
@Table(name = "site_chat_messages", indexes = {
        @Index(name = "ix_site_chat_msg_session", columnList = "session_id"),
        @Index(name = "ix_site_chat_msg_role_created", columnList = "role, created_at")
})
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class SiteChatMessage extends BaseEntity {

    public static final String VISITOR = "VISITOR";
    public static final String ASSISTANT = "ASSISTANT";
    public static final String STAFF = "STAFF";

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** VISITOR | ASSISTANT | STAFF */
    @Column(name = "role", nullable = false, length = 12)
    private String role;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** ASSISTANT: [{"label":"W1","title":"...","url":"..."}] */
    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    /** ASSISTANT: false when the sources did not answer the question. */
    @Column(name = "confident")
    private Boolean confident;

    /** ASSISTANT: the assistant suggested handing over to a person. */
    @Column(name = "handover_suggested")
    private Boolean handoverSuggested;

    /** ASSISTANT: the VISITOR message this answers. */
    @Column(name = "in_reply_to")
    private Long inReplyTo;

    /** STAFF: who wrote it. */
    @Column(name = "author_id")
    private Long authorId;

    /** ASSISTANT: the ai_interactions row, for tracing a bad answer. */
    @Column(name = "interaction_id")
    private Long interactionId;
}
