package com.kashi.grc.incident.repository;

import com.kashi.grc.incident.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, Long>,
        JpaSpecificationExecutor<Incident>,
        IncidentRepositoryCustom {

    Optional<Incident> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    boolean existsByIncidentRefAndTenantId(String incidentRef, Long tenantId);

    Optional<Incident> findByTenantIdAndWorkflowInstanceId(Long tenantId, Long workflowInstanceId);
}
