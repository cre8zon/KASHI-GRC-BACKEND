package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabPlanItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollabPlanItemRepository extends JpaRepository<CollabPlanItem, Long> {

    List<CollabPlanItem> findByWorkspaceIdAndIsDeletedFalse(Long workspaceId);

    Optional<CollabPlanItem> findByIdAndWorkspaceIdAndIsDeletedFalse(Long id, Long workspaceId);

    List<CollabPlanItem> findByTenantIdAndWorkspaceIdInAndIsDeletedFalse(Long tenantId, Collection<Long> workspaceIds);

    List<CollabPlanItem> findByTenantIdAndLinkedEntityTypeAndLinkedEntityIdAndIsDeletedFalse(
            Long tenantId, String linkedEntityType, Long linkedEntityId);
}
