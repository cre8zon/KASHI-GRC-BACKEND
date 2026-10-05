package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Watch progress for one content item within one assignment.
 *
 * ── WHY BOTH COUNTERS EXIST ───────────────────────────────────────────────
 * maxPositionSeconds is the furthest point reached in the timeline.
 * watchedSeconds is how much was actually played.
 *
 * They diverge under exactly the behaviours worth catching:
 *
 *   dragging the scrubber to the end  -> maxPosition jumps, watched does not
 *   2x playback                       -> both advance, wall-clock does not
 *   leaving it playing in a background tab -> both advance honestly, which is
 *                                            a real limitation and is noted in
 *                                            GAPS rather than pretended away
 *
 * So completion needs all three: contiguous coverage of the course's
 * requiredWatchPercent, watchedSeconds within tolerance of that, AND
 * wall-clock elapsed between firstPlayedAt and lastPlayedAt of at least ~80%
 * of the item duration. Any one alone is trivially defeated.
 */
@Entity
@Table(
        name = "training_progress",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_progress_item",
                                  columnNames = {"assignment_id", "item_id"})
        },
        indexes = {
                @Index(name = "idx_tp_assignment", columnList = "assignment_id"),
                @Index(name = "idx_tp_tenant",     columnList = "tenant_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingProgress extends TenantAwareEntity {

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    /** Furthest timeline position reached. Monotonic — never decreases. */
    @Column(name = "max_position_seconds", nullable = false)
    @Builder.Default
    private Integer maxPositionSeconds = 0;

    /** Seconds actually played, accumulated across heartbeats. */
    @Column(name = "watched_seconds", nullable = false)
    @Builder.Default
    private Integer watchedSeconds = 0;

    @Column(name = "first_played_at")
    private LocalDateTime firstPlayedAt;

    @Column(name = "last_played_at")
    private LocalDateTime lastPlayedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
