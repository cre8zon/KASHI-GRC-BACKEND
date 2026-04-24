package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepAssignerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WorkflowStepAssignerRoleRepository
        extends JpaRepository<WorkflowStepAssignerRole, Long> {

    List<WorkflowStepAssignerRole> findByStepId(Long stepId);

    void deleteByStepId(Long stepId);
}