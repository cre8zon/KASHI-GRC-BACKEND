package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.TenantIntegrationCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantIntegrationCheckRepository extends JpaRepository<TenantIntegrationCheck, Long> {

    // ── Primary lookup — used by IntegrationRunner ────────────────────────────

    /**
     * All active checks for a tenant's specific integration.
     * Replaces IntegrationCheckConfigRepository.findByIntegrationKeyAndIsActiveTrue()
     * in IntegrationRunner — now reads tenant-owned rows, not the global library.
     */
    List<TenantIntegrationCheck> findByIntegrationKeyAndTenantIdAndIsActiveTrue(
            String integrationKey, Long tenantId);

    /**
     * All active checks across all integrations for a tenant.
     * Used by EngagementIntegrationSnapshotService to find candidates when
     * snapshotting a new engagement.
     */
    List<TenantIntegrationCheck> findByTenantIdAndIsActiveTrue(Long tenantId);

    // ── Single check lookup ───────────────────────────────────────────────────

    Optional<TenantIntegrationCheck> findByTenantIdAndIntegrationKeyAndCheckKey(
            Long tenantId, String integrationKey, String checkKey);

    Optional<TenantIntegrationCheck> findByTenantIdAndCheckKey(
            Long tenantId, String checkKey);

    boolean existsByTenantIdAndIntegrationKeyAndCheckKey(
            Long tenantId, String integrationKey, String checkKey);

    // ── Deactivation — called when tenant disconnects an integration ──────────

    @Modifying
    @Query("""
        UPDATE TenantIntegrationCheck c
        SET c.isActive = false
        WHERE c.tenantId = :tenantId
          AND c.integrationKey = :integrationKey
    """)
    int deactivateByTenantAndIntegration(
            @Param("tenantId") Long tenantId,
            @Param("integrationKey") String integrationKey);

    // ── Stats for the integration dashboard ───────────────────────────────────

    long countByTenantIdAndIntegrationKeyAndIsActiveTrue(Long tenantId, String integrationKey);

    @Query("""
        SELECT COUNT(c) FROM TenantIntegrationCheck c
        WHERE c.tenantId = :tenantId
          AND c.integrationKey = :integrationKey
          AND c.isActive = true
          AND c.lastRunStatus = 'PASS'
    """)
    long countPassingByTenantAndIntegration(
            @Param("tenantId") Long tenantId,
            @Param("integrationKey") String integrationKey);

    @Query("""
        SELECT COUNT(c) FROM TenantIntegrationCheck c
        WHERE c.tenantId = :tenantId
          AND c.integrationKey = :integrationKey
          AND c.isActive = true
          AND c.lastRunStatus = 'FAIL'
    """)
    long countFailingByTenantAndIntegration(
            @Param("tenantId") Long tenantId,
            @Param("integrationKey") String integrationKey);

    @Query("""
        SELECT COUNT(c) FROM TenantIntegrationCheck c
        WHERE c.tenantId = :tenantId
          AND c.integrationKey = :integrationKey
          AND c.isActive = true
          AND c.lastRunStatus IS NULL
    """)
    long countNeverRunByTenantAndIntegration(
            @Param("tenantId") Long tenantId,
            @Param("integrationKey") String integrationKey);
}