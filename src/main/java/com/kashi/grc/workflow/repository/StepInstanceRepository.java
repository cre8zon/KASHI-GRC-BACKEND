package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.enums.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Derived-name queries only. Lock/SLA/stuck-step queries live in
 * StepInstanceRepositoryCustom, implemented via the JPA Criteria API.
 */
@Repository
public interface StepInstanceRepository
        extends JpaRepository<StepInstance, Long>, StepInstanceRepositoryCustom {

    List<StepInstance> findByWorkflowInstanceIdOrderByCreatedAtAsc(Long workflowInstanceId);

    List<StepInstance> findByWorkflowInstanceId(Long workflowInstanceId);

    List<StepInstance> findByWorkflowInstanceIdAndStatus(Long workflowInstanceId, StepStatus status);

    List<StepInstance> findByStepIdAndStatus(Long stepId, StepStatus status);

    long countByWorkflowInstanceIdAndStatus(Long workflowInstanceId, StepStatus status);

    List<StepInstance> findByWorkflowInstanceIdAndStepId(Long workflowInstanceId, Long stepId);

    List<StepInstance> findByStatus(StepStatus status);
}
