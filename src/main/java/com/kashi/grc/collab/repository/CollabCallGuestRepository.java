package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabCallGuest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollabCallGuestRepository extends JpaRepository<CollabCallGuest, Long> {

    Optional<CollabCallGuest> findByIdAndTenantId(Long id, Long tenantId);

    List<CollabCallGuest> findByTenantIdAndTargetTypeAndTargetIdAndStatusInOrderByRequestedAtAsc(
            Long tenantId, String targetType, Long targetId, Collection<String> statuses);

    long countByTenantIdAndTargetTypeAndTargetIdAndStatus(Long tenantId, String targetType, Long targetId, String status);
}
