package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.WorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, Long> {
    List<WorkflowStep>    findByWorkflowIdOrderByStepOrderAsc(Long workflowId);
    Optional<WorkflowStep> findByWorkflowIdAndStepOrder(Long workflowId, Integer stepOrder);
    Optional<WorkflowStep> findFirstByWorkflowIdOrderByStepOrderAsc(Long workflowId);
    Optional<WorkflowStep> findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(Long workflowId, Integer currentOrder);
    Optional<WorkflowStep> findFirstByWorkflowIdAndStepOrderLessThanOrderByStepOrderDesc(Long workflowId, Integer currentOrder);
    boolean existsByWorkflowIdAndStepOrder(Long workflowId, Integer stepOrder);
    void deleteByWorkflowId(Long workflowId);
}
