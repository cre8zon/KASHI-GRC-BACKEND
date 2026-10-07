package com.kashi.grc.chat.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One person's emoji reaction on one message. Reacting again with the same emoji removes it. */
@Entity
@Table(name = "chat_reactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_reaction", columnNames = {"message_id", "user_id", "emoji"}),
        indexes = @Index(name = "idx_chat_reaction_msg", columnList = "message_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class ChatReaction extends TenantAwareEntity {

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Binary collation on purpose: under general / unicode collations MySQL
     * treats different emoji as EQUAL, and the unique key would refuse a 👎
     * after a 👍.
     */
    @Column(name = "emoji", nullable = false, columnDefinition = "VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
    private String emoji;
}
