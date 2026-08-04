package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditControlRepository extends JpaRepository<AuditControl, Long> {
    List<AuditControl> findByTenantIdIsNullOrTenantId(Long tenantId);

    /** Indexed lookups for CSV library import upsert — were full findAll()+filter per row,
     *  reused across upsertControl, upsertControlTestMapping, upsertPolicyControlMapping. */
    Optional<AuditControl> findByNameAndTenantId(String name, Long tenantId);
    Optional<AuditControl> findByControlCodeAndTenantId(String controlCode, Long tenantId);
}