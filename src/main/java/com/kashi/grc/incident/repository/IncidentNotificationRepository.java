package com.kashi.grc.incident.repository;

import com.kashi.grc.incident.domain.IncidentNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentNotificationRepository extends JpaRepository<IncidentNotification, Long> {

    List<IncidentNotification> findByIncidentIdAndTenantId(Long incidentId, Long tenantId);

    /** Batch: one query for a whole page of incidents. */
    List<IncidentNotification> findByIncidentIdInAndTenantId(Collection<Long> incidentIds, Long tenantId);

    Optional<IncidentNotification> findByIncidentIdAndFrameworkRef(Long incidentId, String frameworkRef);

    /** Overdue across every regime at once — the index exists for exactly this. */
    List<IncidentNotification> findByTenantIdAndNotifiedAtIsNullAndDueAtBefore(Long tenantId, LocalDateTime asOf);

    long countByIncidentIdAndNotifiedAtIsNull(Long incidentId);
}
