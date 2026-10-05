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

/**
 * Who is on a programme. Decides what the FIRM's people can see: a firm member
 * sees a programme (and, later, its plan items, meetings, requests and
 * documents) only when listed here. The client's own people see every
 * programme of a workspace they belong to.
 */
@Entity
@Table(name = "collab_programme_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_collab_prog_member",
                columnNames = {"programme_id", "user_id"}),
        indexes = {
                @Index(name = "idx_collab_pm_ws",   columnList = "workspace_id"),
                @Index(name = "idx_collab_pm_user", columnList = "user_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabProgrammeMember extends TenantAwareEntity {

    @Column(name = "programme_id", nullable = false)
    private Long programmeId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "added_by")
    private Long addedBy;
}
