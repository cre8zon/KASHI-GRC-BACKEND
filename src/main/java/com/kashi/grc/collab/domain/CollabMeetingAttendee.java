package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Someone invited to a meeting, and whether they attended (recorded afterwards). */
@Entity
@Table(name = "collab_meeting_attendees",
        uniqueConstraints = @UniqueConstraint(name = "uk_collab_mt_att", columnNames = {"meeting_id", "user_id"}),
        indexes = @Index(name = "idx_collab_mta_user", columnList = "user_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabMeetingAttendee extends TenantAwareEntity {

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** NULL until recorded after the meeting. */
    @Column(name = "attended")
    private Boolean attended;
}
