package com.kashi.grc.incident.repository;

import com.kashi.grc.incident.domain.IncidentRiskLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentRiskLinkRepository extends JpaRepository<IncidentRiskLink, Long> {
    List<IncidentRiskLink> findByIncidentIdAndTenantId(Long incidentId, Long tenantId);
    Optional<IncidentRiskLink> findByIncidentIdAndRiskId(Long incidentId, Long riskId);
    boolean existsByIncidentIdAndRiskId(Long incidentId, Long riskId);
}
