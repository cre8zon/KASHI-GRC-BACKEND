package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A standing call room — always there, join whenever: "Audit team daily",
 * "ISO 27001 war room". In a workspace (its members, client and firm) or
 * internal (workspace_id NULL: the organisation's own staff). Who may join is
 * its member list (CollabRoomMember). The LiveKit room is room_ref.
 */
@Entity
@Table(name = "collab_rooms",
        indexes = @Index(name = "idx_collab_room_tenant", columnList = "tenant_id, workspace_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabRoom extends AuditableEntity {

    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "room_ref", length = 200)
    private String roomRef;

    @Column(name = "archived", nullable = false)
    @lombok.Builder.Default
    private boolean archived = false;
}
