package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepAssignerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface WorkflowStepAssignerRoleRepository
        extends JpaRepository<WorkflowStepAssignerRole, Long> {

    List<WorkflowStepAssignerRole> findByStepId(Long stepId);

    /**
     * Bulk fetch assigner roles for multiple step IDs in one IN query.
     * Used by WorkflowEngineService.buildWorkflowResponse() to replace
     * the per-step findByStepId pattern (N queries → 1 query).
     */
    List<WorkflowStepAssignerRole> findByStepIdIn(Collection<Long> stepIds);

    @Modifying
    @Query("DELETE FROM WorkflowStepAssignerRole r WHERE r.stepId = :stepId")
    void deleteByStepId(@Param("stepId") Long stepId);
}