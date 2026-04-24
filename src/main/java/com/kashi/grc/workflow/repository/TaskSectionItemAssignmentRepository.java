package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// ================================================================
// TaskSectionItemAssignmentRepository  (Case 3)
// ================================================================
@Repository
public interface TaskSectionItemAssignmentRepository
        extends JpaRepository<TaskSectionItemAssignment, Long> {

    List<TaskSectionItemAssignment> findByItemIdAndIsActive(Long itemId, boolean isActive);

    List<TaskSectionItemAssignment> findByTaskInstanceIdAndSectionKeyAndAssignedToUserIdAndIsActive(
            Long taskInstanceId, String sectionKey, Long assignedToUserId, boolean isActive);

    List<TaskSectionItemAssignment> findByTaskInstanceIdAndSectionKey(
            Long taskInstanceId, String sectionKey);
}