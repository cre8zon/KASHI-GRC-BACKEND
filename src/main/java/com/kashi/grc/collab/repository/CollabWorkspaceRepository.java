package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollabWorkspaceRepository extends JpaRepository<CollabWorkspace, Long> {

    Optional<CollabWorkspace> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    List<CollabWorkspace> findByTenantIdAndIsDeletedFalseOrderByNameAsc(Long tenantId);

    List<CollabWorkspace> findByTenantIdAndIdInAndIsDeletedFalseOrderByNameAsc(Long tenantId, Collection<Long> ids);

    boolean existsByTenantIdAndFirmTenantIdAndStatusAndIsDeletedFalse(Long tenantId, Long firmTenantId, String status);
}
