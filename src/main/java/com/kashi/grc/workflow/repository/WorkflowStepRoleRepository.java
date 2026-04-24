package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.WorkflowStepRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WorkflowStepRoleRepository extends JpaRepository<WorkflowStepRole, Long> {
    List<WorkflowStepRole> findByStepId(Long stepId);
    List<WorkflowStepRole> findByStepIdIn(List<Long> stepIds);
    void deleteByStepId(Long stepId);
}
