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

    /**
     * A tenant's OWN active workflows for an entity type.
     *
     * Workflow extends GlobalOrTenantEntity, so a tenant can publish its own
     * blueprint alongside the global one — but every finder above is
     * findByTenantIdIsNull, which can only ever see the global set. Anything
     * resolving a default workflow server-side therefore had no way to notice
     * that a tenant had authored its own and would silently run the global one
     * instead.
     *
     * Added for VendorServiceImpl.resolveWorkflowId, which prefers a tenant's
     * own blueprint over the global fallback.
     */
    List<Workflow> findByTenantIdAndEntityTypeAndIsActiveTrue(Long tenantId, String entityType);
}
