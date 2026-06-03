package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Full replacement for WorkflowStepSectionRepository.java.
 *
 * Gap 1+2 fix: added deleteByStepId() and deleteByStepIdAndIdNotIn()
 * so saveSteps() and upsertSteps() can manage sections alongside steps.
 *
 * Used ONLY at blueprint edit time and at step activation (snapshotSectionsForTask).
 * Never called during runtime task processing.
 */
@Repository
public interface WorkflowStepSectionRepository extends JpaRepository<WorkflowStepSection, Long> {

    /** Called once per step activation to build the snapshot. */
    List<WorkflowStepSection> findByStepIdOrderBySectionOrderAsc(Long stepId);

    /**
     * Bulk fetch sections for multiple step IDs in one IN query, ordered by section order.
     * Used by WorkflowEngineService.buildWorkflowResponse() to replace
     * the per-step findByStepIdOrderBySectionOrderAsc pattern (N queries → 1 query).
     */
    List<WorkflowStepSection> findByStepIdInOrderBySectionOrderAsc(Collection<Long> stepIds);

    /** Called during workflow activation validation only. */
    boolean existsByStepId(Long stepId);

    // ── Gap 1+2 additions ─────────────────────────────────────────────────────

    /**
     * Bulk DELETE that flushes immediately — avoids Hibernate action-queue
     * ordering issue where a DELETE queued after the INSERT causes a UK
     * constraint violation (uk_wss_step_key) on re-import.
     *
     * Must be @Modifying so Spring Data issues a direct JPQL DELETE
     * instead of select-then-delete (which Hibernate batches after inserts).
     */
    @Modifying
    @Query("DELETE FROM WorkflowStepSection s WHERE s.stepId = :stepId")
    void deleteByStepId(@Param("stepId") Long stepId);

    /**
     * Used by upsertSteps() to delete removed sections while keeping
     * sections whose IDs are still present in the incoming request.
     * Prevents phantom sections from accumulating on blueprint edits.
     */
    void deleteByStepIdAndIdNotIn(Long stepId, List<Long> keepIds);
}