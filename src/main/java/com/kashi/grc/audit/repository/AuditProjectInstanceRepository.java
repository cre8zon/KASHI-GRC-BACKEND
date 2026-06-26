package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditProjectInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AuditProjectInstanceRepository extends JpaRepository<AuditProjectInstance, Long> {

    /** All instances for a given library project — used for list/history views. */
    java.util.List<AuditProjectInstance> findByOriginalProjectId(Long originalProjectId);

    boolean existsByOriginalProjectId(Long originalProjectId);

    long countByOriginalProjectIdAndTenantId(Long originalProjectId, Long tenantId);

    /** All project instances for a tenant — backs GET /v1/audit/project-instances */
    java.util.List<AuditProjectInstance> findByTenantId(Long tenantId);

    java.util.Optional<AuditProjectInstance> findByTenantIdAndId(Long tenantId, Long id);
}