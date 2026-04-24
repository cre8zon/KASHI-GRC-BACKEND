package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    /** Called during workflow activation validation only. */
    boolean existsByStepId(Long stepId);

    // ── Gap 1+2 additions ─────────────────────────────────────────────────────

    /**
     * Used by saveSteps() to wipe all sections before re-inserting.
     * Safe because saveSteps() is only called on blueprint creation
     * (no running instances yet).
     */
    void deleteByStepId(Long stepId);

    /**
     * Used by upsertSteps() to delete removed sections while keeping
     * sections whose IDs are still present in the incoming request.
     * Prevents phantom sections from accumulating on blueprint edits.
     */
    void deleteByStepIdAndIdNotIn(Long stepId, java.util.List<Long> keepIds);
}