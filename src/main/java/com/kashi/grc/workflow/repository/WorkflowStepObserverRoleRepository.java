package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepObserverRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WorkflowStepObserverRoleRepository
        extends JpaRepository<WorkflowStepObserverRole, Long> {

    List<WorkflowStepObserverRole> findByStepId(Long stepId);

    void deleteByStepId(Long stepId);
}