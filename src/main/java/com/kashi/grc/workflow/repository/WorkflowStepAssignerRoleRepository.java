package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepAssignerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

/** deleteByStepId lives in the Custom fragment (CriteriaDelete). */
@Repository
public interface WorkflowStepAssignerRoleRepository
        extends JpaRepository<WorkflowStepAssignerRole, Long>, WorkflowStepAssignerRoleRepositoryCustom {

    List<WorkflowStepAssignerRole> findByStepId(Long stepId);

    List<WorkflowStepAssignerRole> findByStepIdIn(Collection<Long> stepIds);
}
