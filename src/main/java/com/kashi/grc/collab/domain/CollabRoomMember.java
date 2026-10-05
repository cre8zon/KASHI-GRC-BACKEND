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

/** Someone who may join a standing room. */
@Entity
@Table(name = "collab_room_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_collab_room_member", columnNames = {"room_id", "user_id"}),
        indexes = @Index(name = "idx_collab_rm_user", columnList = "user_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabRoomMember extends TenantAwareEntity {

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
