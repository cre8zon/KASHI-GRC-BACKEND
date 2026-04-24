package com.kashi.grc.workflow.repository;
import com.kashi.grc.workflow.domain.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, Long> {
    List<Workflow> findByTenantIdIsNullAndIsActiveTrue();
    List<Workflow> findByTenantIdIsNullAndEntityTypeAndIsActiveTrue(String entityType);
    Optional<Workflow> findByTenantIdIsNullAndNameAndVersion(String name, Integer version);
    boolean existsByTenantIdIsNullAndNameAndVersion(String name, Integer version);
    Optional<Workflow> findTopByTenantIdIsNullAndNameOrderByVersionDesc(String name);
}
