package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AuditControlRepository extends JpaRepository<AuditControl, Long> {
    List<AuditControl> findByTenantIdIsNullOrTenantId(Long tenantId);
}
