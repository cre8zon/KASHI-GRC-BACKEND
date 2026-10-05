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
 * A meeting and its record: who, when, the agenda, the minutes and the
 * decisions. Follow-ups are action items on it (entity_type COLLAB_MEETING),
 * files are document links on it (COLLAB_MEETING).
 *
 * workspace_id NULL = an internal meeting of the organisation, outside any
 * workspace: only its organiser and attendees see it. In a workspace, members
 * who can see its programme (or every member when it has none) see it.
 *
 * The call itself is a link for now (provider + join_url: Teams, Zoom, Meet
 * or any other); the in-product call panel comes in phase 4 and will use the
 * same row (provider EMBEDDED, room_ref).
 */
@Entity
@Table(name = "collab_meetings",
        indexes = {
                @Index(name = "idx_collab_mt_ws",    columnList = "workspace_id"),
                @Index(name = "idx_collab_mt_start", columnList = "tenant_id, starts_at"),
                @Index(name = "idx_collab_mt_org",   columnList = "organizer_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabMeeting extends AuditableEntity {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String HELD      = "HELD";
    public static final String CANCELLED = "CANCELLED";

    /** NULL = internal meeting outside any workspace. */
    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "programme_id")
    private Long programmeId;

    /** Optional engagement the meeting is about (walkthrough, closing meeting). */
    @Column(name = "engagement_id")
    private Long engagementId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** OPENING | WALKTHROUGH | STATUS | CLOSING | INTERNAL | OTHER */
    @Column(name = "kind", nullable = false, length = 20)
    @lombok.Builder.Default
    private String kind = "OTHER";

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    /** NONE | TEAMS | ZOOM | MEET | OTHER | EMBEDDED (phase 4) */
    @Column(name = "provider", nullable = false, length = 20)
    @lombok.Builder.Default
    private String provider = "NONE";

    @Column(name = "join_url", length = 1000)
    private String joinUrl;

    /** Phase 4: the in-product room. */
    @Column(name = "room_ref", length = 200)
    private String roomRef;

    @Column(name = "organizer_id", nullable = false)
    private Long organizerId;

    /** JSON array: [{ "title": "...", "notes": "...", "done": false }] */
    @Column(name = "agenda_json", columnDefinition = "TEXT")
    private String agendaJson;

    @Column(name = "minutes", columnDefinition = "TEXT")
    private String minutes;

    /** JSON array: [{ "text": "...", "agreedBy": "...", "at": "2026-10-04T10:00" }] */
    @Column(name = "decisions_json", columnDefinition = "TEXT")
    private String decisionsJson;

    /** The standing room this meeting runs in (its call is the room's call). NULL = not in a room. */
    @Column(name = "room_id")
    private Long roomId;

    /** Same value on every occurrence of a repeating meeting, so the rest of a series can be cancelled at once. */
    @Column(name = "series_ref", length = 40)
    private String seriesRef;

    /** How the series repeats, for display: "WEEKDAYS|2026-11-05" (rule|until). */
    @Column(name = "series_rule", length = 40)
    private String seriesRule;

    /** When the "starting soon" reminder went out (CollabReminderScheduler) — once per meeting. */
    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;

    /** SCHEDULED | HELD | CANCELLED */
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = SCHEDULED;
}