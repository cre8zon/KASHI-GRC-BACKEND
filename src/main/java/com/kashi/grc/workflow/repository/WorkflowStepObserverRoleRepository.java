package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepObserverRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

/** deleteByStepId lives in the Custom fragment (CriteriaDelete). */
@Repository
public interface WorkflowStepObserverRoleRepository
        extends JpaRepository<WorkflowStepObserverRole, Long>, WorkflowStepObserverRoleRepositoryCustom {

    List<WorkflowStepObserverRole> findByStepId(Long stepId);

    List<WorkflowStepObserverRole> findByStepIdIn(Collection<Long> stepIds);
}
