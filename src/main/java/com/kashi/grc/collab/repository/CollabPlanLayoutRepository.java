package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabPlanLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CollabPlanLayoutRepository extends JpaRepository<CollabPlanLayout, Long> {

    Optional<CollabPlanLayout> findByWorkspaceId(Long workspaceId);
}