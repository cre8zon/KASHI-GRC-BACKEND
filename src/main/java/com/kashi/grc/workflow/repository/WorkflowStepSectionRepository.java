package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.WorkflowStepSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** deleteByStepId lives in the Custom fragment (CriteriaDelete). */
@Repository
public interface WorkflowStepSectionRepository
        extends JpaRepository<WorkflowStepSection, Long>, WorkflowStepSectionRepositoryCustom {

    List<WorkflowStepSection> findByStepIdOrderBySectionOrderAsc(Long stepId);

    List<WorkflowStepSection> findByStepIdInOrderBySectionOrderAsc(Collection<Long> stepIds);

    boolean existsByStepId(Long stepId);

    void deleteByStepIdAndIdNotIn(Long stepId, List<Long> keepIds);
}
