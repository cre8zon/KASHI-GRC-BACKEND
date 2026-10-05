package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabPlanChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CollabPlanChangeRepository extends JpaRepository<CollabPlanChange, Long> {

    List<CollabPlanChange> findByPlanItemIdOrderByCreatedAtDesc(Long planItemId);
}
