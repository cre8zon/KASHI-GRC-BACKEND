package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.WorkflowStepUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WorkflowStepUserRepository extends JpaRepository<WorkflowStepUser, Long> {
    List<WorkflowStepUser> findByStepId(Long stepId);
    List<WorkflowStepUser> findByStepIdIn(List<Long> stepIds);
    void deleteByStepId(Long stepId);
}
