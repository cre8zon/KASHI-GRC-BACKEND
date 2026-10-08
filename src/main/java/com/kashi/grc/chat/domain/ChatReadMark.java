package com.kashi.grc.chat.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One MOVE of somebody's delivered or read pointer. Not one row per message.
 *
 * ── WHAT THIS SOLVES ──────────────────────────────────────────────────────
 *
 * chat_members.last_read_message_id is a high-water mark: it knows how far
 * somebody has read, and when they last read, but not when they read message
 * 137 in particular. So "Read" could be shown on every message and the TIME
 * only on the newest one — which, since the newest message is usually the other
 * person's reply, meant the time almost never appeared.
 *
 * The obvious fix is WhatsApp's: a receipt row per message per recipient. For a
 * 50-person channel with 10,000 messages that is half a million rows, written
 * on every send and aggregated on every render, and it grows with
 * members x messages forever.
 *
 * ── THE SHAPE THAT ACTUALLY FITS ──────────────────────────────────────────
 *
 * A watermark does not creep forward one message at a time. It JUMPS: somebody
 * opens a conversation with 40 unread and their pointer goes from 100 to 140 in
 * one instant. That single event is the fact worth storing, and it already
 * carries the times of all 40 messages:
 *
 *     up_to_message_id = 140, marked_at = 09:14
 *     → every message from 101 to 140 was read at 09:14
 *
 * So one row per ADVANCE, not per message. "When did Karan read message 137?"
 * is the earliest advance of his whose up_to_message_id reaches 137 — exact,
 * for any message however old, which is what a per-message table would have
 * told you. A reader who opens a conversation twenty times a day writes twenty
 * rows, against the hundreds the per-message shape would have written.
 *
 * ── WHY THE WATERMARK COLUMNS STAY ────────────────────────────────────────
 *
 * They are the hot path. Unread counts, and the tick on every message in a
 * page, are one integer comparison against chat_members. This table is only
 * ever read when somebody opens the info panel on one message. Keeping both
 * means the common case costs nothing and the rare case is exact — collapsing
 * them into one would make every render pay for a query almost nobody runs.
 *
 * Rows written before this table existed have no history, so their times come
 * back null and the UI shows the state without a time. That is a fact about
 * what was recorded, not a failure to read it.
 */
@Entity
@Table(name = "chat_read_marks",
        indexes = {
                // The panel's query: one conversation, one member, one kind, a
                // range on up_to_message_id. Leading the index with the equality
                // columns lets MySQL range-scan and return rows already grouped,
                // so the MIN() needs no sort.
                @Index(name = "idx_chat_mark_lookup",
                        columnList = "conversation_id, user_id, kind, up_to_message_id"),
                // Housekeeping: find and prune a conversation's old marks.
                @Index(name = "idx_chat_mark_conv", columnList = "conversation_id, marked_at")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class ChatReadMark extends TenantAwareEntity {

    public static final String DELIVERED = "DELIVERED";
    public static final String READ      = "READ";

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** Whose pointer moved. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** DELIVERED or READ. The two pointers advance independently. */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    /**
     * Where the pointer landed. Everything in this conversation at or below this
     * id, and above where the pointer was before, happened at markedAt.
     */
    @Column(name = "up_to_message_id", nullable = false)
    private Long upToMessageId;

    /**
     * When it moved. Explicit rather than leaning on created_at: this is the
     * fact the row exists to record, and it is set from the same instant as the
     * watermark it mirrors, so the two can never disagree by a flush.
     */
    @Column(name = "marked_at", nullable = false)
    private LocalDateTime markedAt;
}