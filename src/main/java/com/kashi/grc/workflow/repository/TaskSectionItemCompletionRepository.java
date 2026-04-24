package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// ================================================================
// TaskSectionItemCompletionRepository  (Case 3)
// ================================================================
@Repository
public interface TaskSectionItemCompletionRepository
        extends JpaRepository<TaskSectionItemCompletion, Long> {

    List<TaskSectionItemCompletion> findByTaskInstanceIdAndSectionKey(
            Long taskInstanceId, String sectionKey);

    boolean existsByItemId(Long itemId);

    long countByTaskInstanceIdAndSectionKey(Long taskInstanceId, String sectionKey);
}