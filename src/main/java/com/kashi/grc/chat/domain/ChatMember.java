package com.kashi.grc.chat.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Someone in a conversation, and how far they have received and read. */
@Entity
@Table(name = "chat_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_member", columnNames = {"conversation_id", "user_id"}),
        indexes = @Index(name = "idx_chat_member_user", columnList = "tenant_id, user_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class ChatMember extends TenantAwareEntity {

    public static final String OWNER  = "OWNER";
    public static final String MEMBER = "MEMBER";

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role", nullable = false, length = 10)
    @lombok.Builder.Default
    private String role = MEMBER;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    /**
     * How far this person's client has RECEIVED, as opposed to how far they have
     * read. The second tick.
     *
     * Advanced from three places, cheapest first: the app-wide chat socket
     * reports it the moment a push lands in any of their tabs (the honest
     * "reached the device" moment, and it works whether or not they are looking
     * at chat); opening the conversation advances it to the newest message
     * fetched; and reading advances it too, because you cannot read what was
     * never delivered.
     *
     * -- WHY A WATERMARK AND NOT A ROW PER MESSAGE --------------------------
     *
     * A chat_message_receipts table would be members x messages: a 50-person
     * channel with 10,000 messages is half a million rows to write on every
     * send and aggregate on every render. The watermark answers every question
     * the UI actually asks -- "has this person seen message M?" is
     * lastReadMessageId >= M.id -- in the same shape unreadCounts already uses,
     * with one row per member for all time.
     *
     * What it cannot answer is WHEN a specific older message was read. The
     * timestamps below are when the watermark last moved, so they are exact for
     * the newest message and an upper bound for anything behind it. The UI only
     * shows a time where that holds.
     */
    @Column(name = "last_delivered_message_id")
    private Long lastDeliveredMessageId;

    /** When last_delivered_message_id last moved. */
    @Column(name = "last_delivered_at")
    private LocalDateTime lastDeliveredAt;

    /** When last_read_message_id last moved. */
    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

    @Column(name = "muted", nullable = false)
    @lombok.Builder.Default
    private boolean muted = false;
}