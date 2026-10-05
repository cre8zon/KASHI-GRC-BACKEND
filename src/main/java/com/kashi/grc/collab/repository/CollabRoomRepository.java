package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollabRoomRepository extends JpaRepository<CollabRoom, Long> {

    Optional<CollabRoom> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    List<CollabRoom> findByTenantIdAndIdInAndIsDeletedFalse(Long tenantId, Collection<Long> ids);
}
