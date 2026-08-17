package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.TenantIntegrationCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Deactivation + status counts live in the Custom fragment (Criteria API). */
@Repository
public interface TenantIntegrationCheckRepository
        extends JpaRepository<TenantIntegrationCheck, Long>, TenantIntegrationCheckRepositoryCustom {

    List<TenantIntegrationCheck> findByIntegrationKeyAndTenantIdAndIsActiveTrue(
            String integrationKey, Long tenantId);

    List<TenantIntegrationCheck> findByTenantIdAndIsActiveTrue(Long tenantId);

    Optional<TenantIntegrationCheck> findByTenantIdAndIntegrationKeyAndCheckKey(
            Long tenantId, String integrationKey, String checkKey);

    /** Capability-based resolution: the ACTIVE check this tenant connected that
     *  provides the given capability (e.g. MFA_ADMIN). If a tenant somehow connected
     *  two providers for the same capability, the first active one wins. */
    java.util.List<TenantIntegrationCheck> findByTenantIdAndCapabilityAndIsActiveTrue(
            Long tenantId, String capability);

    Optional<TenantIntegrationCheck> findByTenantIdAndCheckKey(
            Long tenantId, String checkKey);

    boolean existsByTenantIdAndIntegrationKeyAndCheckKey(
            Long tenantId, String integrationKey, String checkKey);

    long countByTenantIdAndIntegrationKeyAndIsActiveTrue(Long tenantId, String integrationKey);
}