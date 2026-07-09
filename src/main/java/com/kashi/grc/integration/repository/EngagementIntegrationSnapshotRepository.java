package com.kashi.grc.integration.repository;

import com.kashi.grc.integration.domain.EngagementIntegrationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Deactivation + result counts live in the Custom fragment (Criteria API). */
@Repository
public interface EngagementIntegrationSnapshotRepository
        extends JpaRepository<EngagementIntegrationSnapshot, Long>,
        EngagementIntegrationSnapshotRepositoryCustom {

    List<EngagementIntegrationSnapshot> findByEngagementIdAndTenantId(
            Long engagementId, Long tenantId);

    List<EngagementIntegrationSnapshot> findByEngagementIdAndIsActiveTrueAndTenantId(
            Long engagementId, Long tenantId);

    List<EngagementIntegrationSnapshot> findByCheckKeyAndTenantIdAndIsActiveTrue(
            String checkKey, Long tenantId);

    boolean existsByEngagementIdAndTestInstanceIdAndCheckKey(
            Long engagementId, Long testInstanceId, String checkKey);
}
