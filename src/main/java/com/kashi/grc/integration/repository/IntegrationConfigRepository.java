package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.IntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, Long> {
    List<IntegrationConfig> findByTenantId(Long tenantId);
    List<IntegrationConfig> findByIsActiveTrue();
    Optional<IntegrationConfig> findByTenantIdAndIntegrationKey(Long tenantId, String integrationKey);
}