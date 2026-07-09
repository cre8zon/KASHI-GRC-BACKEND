package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskSectionAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** countIncomplete lives in the Custom fragment (Criteria API). */
@Repository
public interface TaskSectionAssignmentRepository
        extends JpaRepository<TaskSectionAssignment, Long>, TaskSectionAssignmentRepositoryCustom {

    List<TaskSectionAssignment> findByTaskInstanceIdAndSectionKey(
            Long taskInstanceId, String sectionKey);

    List<TaskSectionAssignment> findByTaskInstanceId(Long taskInstanceId);

    Optional<TaskSectionAssignment> findBySubTaskInstanceId(Long subTaskInstanceId);

    boolean existsByTaskInstanceIdAndSectionKeyAndAssignedToUserId(
            Long taskInstanceId, String sectionKey, Long assignedToUserId);
}
