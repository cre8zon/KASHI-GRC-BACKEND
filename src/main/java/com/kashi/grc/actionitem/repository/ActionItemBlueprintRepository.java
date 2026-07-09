package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItemBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/** Tenant-overlay lists live in the Custom fragment (Criteria API). */
public interface ActionItemBlueprintRepository
    extends JpaRepository<ActionItemBlueprint, Long>,
            JpaSpecificationExecutor<ActionItemBlueprint>,
            ActionItemBlueprintRepositoryCustom {

    Optional<ActionItemBlueprint> findByBlueprintCode(String code);
}
