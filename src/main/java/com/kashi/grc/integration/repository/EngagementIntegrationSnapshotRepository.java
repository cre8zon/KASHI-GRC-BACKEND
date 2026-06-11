package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.EngagementIntegrationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EngagementIntegrationSnapshotRepository
        extends JpaRepository<EngagementIntegrationSnapshot, Long> {

    // ── Lookup by engagement ──────────────────────────────────────────────────

    List<EngagementIntegrationSnapshot> findByEngagementIdAndTenantId(
            Long engagementId, Long tenantId);

    List<EngagementIntegrationSnapshot> findByEngagementIdAndIsActiveTrueAndTenantId(
            Long engagementId, Long tenantId);

    // ── Lookup by check key — used by IntegrationRunner to find targets ───────

    /**
     * Called by EngagementIntegrationSnapshotService.recordResult() when a check
     * completes. Returns all active snapshots for this check+tenant combination so
     * results can be pushed to the correct test instances.
     */
    List<EngagementIntegrationSnapshot> findByCheckKeyAndTenantIdAndIsActiveTrue(
            String checkKey, Long tenantId);

    // ── Duplicate check during snapshotting ───────────────────────────────────

    boolean existsByEngagementIdAndTestInstanceIdAndCheckKey(
            Long engagementId, Long testInstanceId, String checkKey);

    // ── Deactivation — called when engagement closes ──────────────────────────

    @Modifying
    @Query("""
        UPDATE EngagementIntegrationSnapshot s
        SET s.isActive = false
        WHERE s.engagementId = :engagementId
          AND s.tenantId = :tenantId
    """)
    int deactivateByEngagementId(
            @Param("engagementId") Long engagementId,
            @Param("tenantId") Long tenantId);

    // ── Stats for engagement overview ─────────────────────────────────────────

    @Query("""
        SELECT COUNT(s) FROM EngagementIntegrationSnapshot s
        WHERE s.engagementId = :engagementId
          AND s.tenantId = :tenantId
          AND s.lastResult = 'PASS'
    """)
    long countPassingByEngagementId(
            @Param("engagementId") Long engagementId,
            @Param("tenantId") Long tenantId);

    @Query("""
        SELECT COUNT(s) FROM EngagementIntegrationSnapshot s
        WHERE s.engagementId = :engagementId
          AND s.tenantId = :tenantId
          AND s.lastResult = 'FAIL'
    """)
    long countFailingByEngagementId(
            @Param("engagementId") Long engagementId,
            @Param("tenantId") Long tenantId);

    @Query("""
        SELECT COUNT(s) FROM EngagementIntegrationSnapshot s
        WHERE s.engagementId = :engagementId
          AND s.tenantId = :tenantId
          AND s.lastResult = 'NOT_RUN'
    """)
    long countNeverRunByEngagementId(
            @Param("engagementId") Long engagementId,
            @Param("tenantId") Long tenantId);
}