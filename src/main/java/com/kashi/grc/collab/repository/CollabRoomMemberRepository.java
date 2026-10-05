package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollabRoomMemberRepository extends JpaRepository<CollabRoomMember, Long> {

    List<CollabRoomMember> findByRoomId(Long roomId);

    List<CollabRoomMember> findByRoomIdIn(Collection<Long> roomIds);

    List<CollabRoomMember> findByTenantIdAndUserId(Long tenantId, Long userId);

    Optional<CollabRoomMember> findByRoomIdAndUserId(Long roomId, Long userId);
}
