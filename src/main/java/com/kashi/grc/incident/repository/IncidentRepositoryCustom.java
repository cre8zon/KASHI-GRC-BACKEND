package com.kashi.grc.incident.repository;

import com.kashi.grc.incident.domain.Incident;

import java.util.List;

public interface IncidentRepositoryCustom {

    long nextIncidentRefSequence(Long tenantId);

    List<Object[]> countByStatusForTenant(Long tenantId);

    List<Object[]> countBySeverityForTenant(Long tenantId);

    List<Object[]> countByTypeForTenant(Long tenantId);

    /** Open incidents whose internal resolution SLA has passed. */
    List<Incident> findSlaBreached(Long tenantId);

    /**
     * Mean time to detect and mean time to contain, in hours, over closed
     * incidents that recorded both ends of each pair.
     *
     * Returned as [mttdHours, mttcHours]. Null where no incident has enough
     * data — reporting 0.0 for "we have never measured this" would be worse
     * than an empty dashboard tile.
     */
    Double[] meanTimesInHours(Long tenantId);
}
