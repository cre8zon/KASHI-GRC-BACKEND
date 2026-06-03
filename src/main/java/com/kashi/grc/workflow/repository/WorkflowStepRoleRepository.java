package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.WorkflowStepRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WorkflowStepRoleRepository extends JpaRepository<WorkflowStepRole, Long> {
    List<WorkflowStepRole> findByStepId(Long stepId);
    List<WorkflowStepRole> findByStepIdIn(List<Long> stepIds);

    @Modifying
    @Query("DELETE FROM WorkflowStepRole r WHERE r.stepId = :stepId")
    void deleteByStepId(@Param("stepId") Long stepId);
}