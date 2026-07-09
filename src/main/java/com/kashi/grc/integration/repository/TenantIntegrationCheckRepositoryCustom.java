package com.kashi.grc.integration.repository;

/** Criteria API fragment for TenantIntegrationCheckRepository. */
public interface TenantIntegrationCheckRepositoryCustom {

    /** Deactivate all checks when a tenant disconnects an integration. Caller must be @Transactional. */
    int deactivateByTenantAndIntegration(Long tenantId, String integrationKey);

    /** Count active checks whose last run PASSed. */
    long countPassingByTenantAndIntegration(Long tenantId, String integrationKey);

    /** Count active checks whose last run FAILed. */
    long countFailingByTenantAndIntegration(Long tenantId, String integrationKey);

    /** Count active checks never run (lastRunStatus IS NULL). */
    long countNeverRunByTenantAndIntegration(Long tenantId, String integrationKey);
}
