package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabWorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollabWorkspaceMemberRepository extends JpaRepository<CollabWorkspaceMember, Long> {

    List<CollabWorkspaceMember> findByWorkspaceId(Long workspaceId);

    List<CollabWorkspaceMember> findByTenantIdAndUserId(Long tenantId, Long userId);

    Optional<CollabWorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    long countByWorkspaceIdAndWorkspaceRole(Long workspaceId, String workspaceRole);
}
