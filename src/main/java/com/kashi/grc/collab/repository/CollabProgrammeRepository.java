package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabProgramme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollabProgrammeRepository extends JpaRepository<CollabProgramme, Long> {

    List<CollabProgramme> findByWorkspaceIdAndIsDeletedFalseOrderByPlannedStartAscNameAsc(Long workspaceId);

    Optional<CollabProgramme> findByIdAndWorkspaceIdAndIsDeletedFalse(Long id, Long workspaceId);
}