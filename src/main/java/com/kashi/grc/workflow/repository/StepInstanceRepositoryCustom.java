package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.StepInstance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Criteria API fragment for StepInstanceRepository. */
public interface StepInstanceRepositoryCustom {

    /**
     * Load a step instance with a PESSIMISTIC_WRITE row lock (SELECT ... FOR UPDATE).
     * Guards concurrent step advancement — the SLA_BREACHED concurrent-advance fix
     * in WorkflowEngineService depends on this lock. Caller must be @Transactional.
     */
    Optional<StepInstance> findByIdForUpdate(Long id);

    /** IN_PROGRESS steps past their SLA due time and not completed — StepSlaMonitor. */
    List<StepInstance> findAllSlaBreached(LocalDateTime now);

    /**
     * IN_PROGRESS steps with no live ACTOR task (none PENDING or APPROVED) —
     * stuck-step detection.
     */
    List<StepInstance> findStuckSteps();
}
