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

/** Someone in a conversation, and how far they have read. */
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

    @Column(name = "muted", nullable = false)
    @lombok.Builder.Default
    private boolean muted = false;
}
