package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// ================================================================
// TaskSectionAssignmentRepository  (Case 2)
// ================================================================
@Repository
public interface TaskSectionAssignmentRepository extends JpaRepository<TaskSectionAssignment, Long> {

    List<TaskSectionAssignment> findByTaskInstanceIdAndSectionKey(
            Long taskInstanceId, String sectionKey);

    List<TaskSectionAssignment> findByTaskInstanceId(Long taskInstanceId);

    Optional<TaskSectionAssignment> findBySubTaskInstanceId(Long subTaskInstanceId);

    boolean existsByTaskInstanceIdAndSectionKeyAndAssignedToUserId(
            Long taskInstanceId, String sectionKey, Long assignedToUserId);

    @Query("SELECT COUNT(a) FROM TaskSectionAssignment a " +
            "WHERE a.taskInstanceId = :taskInstanceId " +
            "AND a.sectionKey = :sectionKey AND a.status != 'COMPLETED'")
    long countIncomplete(
            @Param("taskInstanceId") Long taskInstanceId,
            @Param("sectionKey") String sectionKey);
}