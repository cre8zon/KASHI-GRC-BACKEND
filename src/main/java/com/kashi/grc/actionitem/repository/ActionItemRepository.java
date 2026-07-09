package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** All query logic lives in ActionItemRepositoryCustom (JPA Criteria API). */
public interface ActionItemRepository
        extends JpaRepository<ActionItem, Long>,
        JpaSpecificationExecutor<ActionItem>,
        ActionItemRepositoryCustom {
}
