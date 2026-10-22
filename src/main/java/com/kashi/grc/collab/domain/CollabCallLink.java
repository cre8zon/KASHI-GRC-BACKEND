package com.kashi.grc.collab.domain;

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
 * A shareable link to an in-app call: {origin}/join/{code}.
 *
 * Points at a MEETING (its call) or a standing ROOM (whatever session is on in
 * it — a room link stays the same from one stand-up to the next). One live
 * link per target; "new link" revokes the old one.
 *
 * Who gets in with it (CollabCallLinkService):
 *   • people of the same organisation who could already see the call join
 *     directly — the link is just the invitation;
 *   • anyone else, signed in elsewhere or not signed in at all, asks to join
 *     as a GUEST and waits until someone in the call admits them — and only
 *     while allow_guests is on.
 *
 * The code is 80 random bits ("abcd-efgh-jkmn-pqrs"), unique across tenants,
 * because the public endpoints find the link — and so the tenant — by code
 * alone. It is a capability, like a Meet code: revocable, expiring, and never
 * enough on its own for a guest, who also needs a person to admit them.
 */
@Entity
@Table(name = "collab_call_links",
        indexes = {
                @Index(name = "uk_collab_call_link_code", columnList = "code", unique = true),
                @Index(name = "idx_collab_call_link_target", columnList = "tenant_id, target_type, target_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabCallLink extends AuditableEntity {

    public static final String MEETING = "MEETING";
    public static final String ROOM    = "ROOM";

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "allow_guests", nullable = false)
    @lombok.Builder.Default
    private boolean allowGuests = true;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked", nullable = false)
    @lombok.Builder.Default
    private boolean revoked = false;

    public boolean usable(LocalDateTime now) {
        return !revoked && !isDeleted() && expiresAt != null && expiresAt.isAfter(now);
    }
}
