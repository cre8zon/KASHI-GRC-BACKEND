package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.IntegrationCheckConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public interface IntegrationCheckConfigRepository extends JpaRepository<IntegrationCheckConfig, Long> {
    List<IntegrationCheckConfig> findByIntegrationKeyAndIsActiveTrue(String integrationKey);
    Optional<IntegrationCheckConfig> findByIntegrationKeyAndCheckKey(String integrationKey, String checkKey);
    long countByIntegrationKeyAndIsActiveTrue(String integrationKey);
}