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

/** One message. Deleting keeps the row (deleted = true, body cleared) so replies and order stay intact. */
@Entity
@Table(name = "chat_messages",
        indexes = @Index(name = "idx_chat_msg_conv", columnList = "conversation_id, id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class ChatMessage extends TenantAwareEntity {

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Comma-separated user ids mentioned with @. */
    @Column(name = "mentions", length = 1000)
    private String mentions;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "deleted", nullable = false)
    @lombok.Builder.Default
    private boolean deleted = false;
}
