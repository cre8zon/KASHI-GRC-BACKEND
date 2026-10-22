package com.kashi.grc.collab.domain;

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
 * Someone without an account (or from another organisation) asking to join a
 * call through its link — the call's lobby.
 *
 *   WAITING   asked; shown to the people in the call while the guest's page
 *             keeps polling (last_seen_at). Gone after 15 minutes unanswered.
 *   ADMITTED  someone in the call let them in. The guest's page may fetch a
 *             join token with its secret until admitted_until (so a refresh on
 *             their side gets them back in without asking again).
 *   DENIED    turned away.            LEFT     they gave up waiting.
 *   REMOVED   put out of the call.    EXPIRED  nobody answered in time.
 *
 * The guest proves it is them with a 256-bit secret given once at knock time;
 * only its SHA-256 is stored. No IP address or device data is kept — the
 * row is the record that a named guest attended, and who admitted them.
 */
@Entity
@Table(name = "collab_call_guests",
        indexes = @Index(name = "idx_collab_call_guest_target", columnList = "tenant_id, target_type, target_id, status"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabCallGuest extends TenantAwareEntity {

    public static final String WAITING  = "WAITING";
    public static final String ADMITTED = "ADMITTED";
    public static final String DENIED   = "DENIED";
    public static final String LEFT     = "LEFT";
    public static final String REMOVED  = "REMOVED";
    public static final String EXPIRED  = "EXPIRED";

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    /** MEETING or ROOM — what the link pointed at (survives a regenerated link). */
    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** The meeting record the guest attended (a room's session), once admitted. */
    @Column(name = "meeting_id")
    private Long meetingId;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "admitted_until")
    private LocalDateTime admittedUntil;

    /** The LiveKit identity of the guest's latest token (to remove them). */
    @Column(name = "identity", length = 64)
    private String identity;

    /** The LiveKit room they were let into. */
    @Column(name = "room_ref", length = 200)
    private String roomRef;
}
