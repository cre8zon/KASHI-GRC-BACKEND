package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditProjectTenantAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditProjectTenantAccessRepository extends JpaRepository<AuditProjectTenantAccess, Long> {

    List<AuditProjectTenantAccess> findByProjectId(Long projectId);

    boolean existsByProjectIdAndTenantId(Long projectId, Long tenantId);

    void deleteByProjectIdAndTenantId(Long projectId, Long tenantId);

    /** All access rows for a tenant — caller maps to project IDs */
    List<AuditProjectTenantAccess> findByTenantId(Long tenantId);
}