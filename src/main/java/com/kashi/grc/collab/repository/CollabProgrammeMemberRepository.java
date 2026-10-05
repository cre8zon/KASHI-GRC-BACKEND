package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabProgrammeMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollabProgrammeMemberRepository extends JpaRepository<CollabProgrammeMember, Long> {

    List<CollabProgrammeMember> findByWorkspaceId(Long workspaceId);

    List<CollabProgrammeMember> findByProgrammeId(Long programmeId);

    Optional<CollabProgrammeMember> findByProgrammeIdAndUserId(Long programmeId, Long userId);

    void deleteByWorkspaceIdAndUserId(Long workspaceId, Long userId);
}
