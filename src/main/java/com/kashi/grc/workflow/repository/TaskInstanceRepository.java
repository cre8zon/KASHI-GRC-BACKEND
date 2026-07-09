package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Derived-name queries only. Workflow-instance-scoped lookups live in
 * TaskInstanceRepositoryCustom, implemented via the JPA Criteria API.
 * findByAssignedUserIdAndStatusIn needed no @Query at all — the method name
 * is a valid derived query.
 */
@Repository
public interface TaskInstanceRepository
        extends JpaRepository<TaskInstance, Long>, TaskInstanceRepositoryCustom {

    List<TaskInstance> findByStepInstanceId(Long stepInstanceId);

    List<TaskInstance> findByStepInstanceIdAndStatus(Long stepInstanceId, TaskStatus status);

    List<TaskInstance> findByAssignedUserIdAndStatus(Long assignedUserId, TaskStatus status);

    List<TaskInstance> findByAssignedUserId(Long assignedUserId);

    long countByStepInstanceId(Long stepInstanceId);

    long countByStepInstanceIdAndStatus(Long stepInstanceId, TaskStatus status);

    List<TaskInstance> findByStepInstanceIdIn(List<Long> stepInstanceIds);

    List<TaskInstance> findByStepInstanceIdInAndStatus(List<Long> stepInstanceIds, TaskStatus status);

    Optional<TaskInstance> findByIdAndAssignedUserId(Long id, Long assignedUserId);

    long countByStepInstanceIdAndTaskRole(Long stepInstanceId, TaskRole taskRole);

    long countByStepInstanceIdAndTaskRoleAndStatus(Long stepInstanceId, TaskRole taskRole, TaskStatus status);

    long countByStepInstanceIdAndTaskRoleAndStatusNot(Long stepInstanceId, TaskRole taskRole, TaskStatus status);

    boolean existsByStepInstanceIdAndAssignedUserIdAndStatus(
            Long stepInstanceId, Long assignedUserId, TaskStatus status);

    // Former @Query — the name alone derives "assignedUserId = ? AND status IN ?"
    List<TaskInstance> findByAssignedUserIdAndStatusIn(Long userId, Collection<TaskStatus> statuses);
}
