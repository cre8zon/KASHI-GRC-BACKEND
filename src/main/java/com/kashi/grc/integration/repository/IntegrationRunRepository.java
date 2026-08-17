package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.IntegrationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public interface IntegrationRunRepository extends JpaRepository<IntegrationRun, Long> {
    List<IntegrationRun> findTop50ByTenantIdOrderByRunAtDesc(Long tenantId);
    List<IntegrationRun> findTop50ByTenantIdAndIntegrationConfigIdOrderByRunAtDesc(
            Long tenantId, Long integrationConfigId);
    List<IntegrationRun> findByTenantIdAndCheckKeyOrderByRunAtDesc(Long tenantId, String checkKey);
    Optional<IntegrationRun> findByIdAndTenantId(Long id, Long tenantId);
}