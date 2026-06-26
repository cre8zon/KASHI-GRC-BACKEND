package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditProjectRepository extends JpaRepository<AuditProject, Long>,
        JpaSpecificationExecutor<AuditProject> {

    /** Org-private projects only */
    List<AuditProject> findByTenantId(Long tenantId);

    /** Global + org-private — used for list endpoints visible to org users */
    List<AuditProject> findByTenantIdIsNullOrTenantId(Long tenantId);

    /** Org-scoped find by id — used by engagement service to validate project ownership */
    Optional<AuditProject> findByTenantIdAndId(Long tenantId, Long id);

    /** Count for ref generation — counts global + tenant projects */
    long countByTenantIdIsNullOrTenantId(Long tenantId);
}