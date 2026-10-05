package com.kashi.grc.chat.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A conversation in internal chat — the organisation's own staff only.
 *   DIRECT   two people; direct_key "minUserId:maxUserId" keeps one per pair
 *   GROUP    a few people, no name needed
 *   CHANNEL  named; PUBLIC (any staff member can find and join it) or PRIVATE
 *            (invite only)
 */
@Entity
@Table(name = "chat_conversations",
        indexes = {
                @Index(name = "idx_chat_conv_tenant", columnList = "tenant_id, kind"),
                @Index(name = "idx_chat_conv_direct", columnList = "tenant_id, direct_key")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class ChatConversation extends AuditableEntity {

    public static final String DIRECT  = "DIRECT";
    public static final String GROUP   = "GROUP";
    public static final String CHANNEL = "CHANNEL";
    public static final String PUBLIC  = "PUBLIC";
    public static final String PRIVATE = "PRIVATE";

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** PUBLIC | PRIVATE — channels only. */
    @Column(name = "visibility", length = 10)
    private String visibility;

    @Column(name = "direct_key", length = 50)
    private String directKey;

    @Column(name = "archived", nullable = false)
    @lombok.Builder.Default
    private boolean archived = false;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;
}
