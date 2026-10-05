package com.kashi.grc.incident.repository;

import com.kashi.grc.incident.domain.IncidentAssetLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentAssetLinkRepository extends JpaRepository<IncidentAssetLink, Long> {
    List<IncidentAssetLink> findByIncidentIdAndTenantId(Long incidentId, Long tenantId);
    Optional<IncidentAssetLink> findByIncidentIdAndAssetId(Long incidentId, Long assetId);
    boolean existsByIncidentIdAndAssetId(Long incidentId, Long assetId);
}
