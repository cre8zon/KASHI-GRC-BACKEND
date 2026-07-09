package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.WorkflowStepRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** deleteByStepId lives in the Custom fragment (CriteriaDelete). */
@Repository
public interface WorkflowStepRoleRepository
        extends JpaRepository<WorkflowStepRole, Long>, WorkflowStepRoleRepositoryCustom {
    List<WorkflowStepRole> findByStepId(Long stepId);
    List<WorkflowStepRole> findByStepIdIn(List<Long> stepIds);
}
