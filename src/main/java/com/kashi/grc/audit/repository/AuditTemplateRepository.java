package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditTemplate;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;

@Repository
public interface AuditTemplateRepository extends JpaRepository<AuditTemplate, Long>,
        JpaSpecificationExecutor<AuditTemplate> {
    List<AuditTemplate> findByTenantIdIsNullOrTenantId(Long tenantId);
    Optional<AuditTemplate> findByIdAndTenantId(Long id, Long tenantId);
    List<AuditTemplate> findByStatus(String status);
    boolean existsByNameAndTenantId(String name, Long tenantId);
    boolean existsByTemplateNameAndTenantId(String templateName, Long tenantId);
}
