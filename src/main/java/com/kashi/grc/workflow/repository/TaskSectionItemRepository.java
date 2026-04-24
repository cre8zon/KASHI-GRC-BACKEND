package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// ================================================================
// TaskSectionItemRepository  (Case 3)
// ================================================================
@Repository
public interface TaskSectionItemRepository extends JpaRepository<TaskSectionItem, Long> {

    List<TaskSectionItem> findByTaskInstanceIdAndSectionKey(
            Long taskInstanceId, String sectionKey);

    long countByTaskInstanceIdAndSectionKey(Long taskInstanceId, String sectionKey);

    long countByTaskInstanceIdAndSectionKeyAndStatus(
            Long taskInstanceId, String sectionKey, String status);
}
