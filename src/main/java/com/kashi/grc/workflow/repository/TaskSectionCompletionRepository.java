package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// ================================================================
// TaskSectionCompletionRepository
// All runtime queries. Uses snap_* columns exclusively.
// workflow_step_sections is never joined here.
// ================================================================
@Repository
public interface TaskSectionCompletionRepository extends JpaRepository<TaskSectionCompletion, Long> {

    /** Full snapshot list for a task — used by progress queries and gate check. */
    List<TaskSectionCompletion> findByTaskInstanceIdOrderBySnapSectionOrderAsc(Long taskInstanceId);

    /** Find specific section by snapshotted key. */
    Optional<TaskSectionCompletion> findByTaskInstanceIdAndSnapSectionKey(
            Long taskInstanceId, String snapSectionKey);

    /**
     * Match incoming TaskSectionEvent against snapshotted completion_event key.
     * This is how events are routed without any blueprint read.
     * Index on snap_completion_event makes this fast.
     */
    Optional<TaskSectionCompletion> findByTaskInstanceIdAndSnapCompletionEvent(
            Long taskInstanceId, String snapCompletionEvent);

    /** Count completed required sections — used by gate check. */
    @Query("SELECT COUNT(c) FROM TaskSectionCompletion c " +
            "WHERE c.taskInstanceId = :taskInstanceId " +
            "AND c.snapRequired = TRUE AND c.completed = TRUE")
    long countCompletedRequired(@Param("taskInstanceId") Long taskInstanceId);

    /** Count total required sections — used by gate check. */
    @Query("SELECT COUNT(c) FROM TaskSectionCompletion c " +
            "WHERE c.taskInstanceId = :taskInstanceId " +
            "AND c.snapRequired = TRUE")
    long countTotalRequired(@Param("taskInstanceId") Long taskInstanceId);

    /** Check if a task has any snapshotted sections at all. */
    boolean existsByTaskInstanceId(Long taskInstanceId);

    /** All incomplete required sections — used by validateReadyForApproval. */
    @Query("SELECT c FROM TaskSectionCompletion c " +
            "WHERE c.taskInstanceId = :taskInstanceId " +
            "AND c.snapRequired = TRUE AND c.completed = FALSE")
    List<TaskSectionCompletion> findIncompleteRequired(@Param("taskInstanceId") Long taskInstanceId);

    List<TaskSectionCompletion> findByStepInstanceId(Long stepInstanceId);
}