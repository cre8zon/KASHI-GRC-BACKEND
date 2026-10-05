package com.kashi.grc.incident.workflow;

import com.kashi.grc.incident.domain.Incident;
import com.kashi.grc.incident.repository.IncidentRepository;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves the artifact for INCIDENT workflow instances.
 *
 * WorkflowInstance.entityId IS the incident id — IncidentService sets it
 * directly on startWorkflow, so there is no indirection to unwind.
 *
 * Discovered through @Component plus WorkflowEntityResolverRegistry; nothing in
 * WorkflowEngineService or TaskInbox needs changing.
 *
 * ── ROUTING ───────────────────────────────────────────────────────────────
 * TaskInbox.resolveTaskRoute looks a step's navKey up in ui_navigation. The
 * seed adds an incident_detail row with is_active = 0 and route
 * /module/incident/:id for exactly this — getNavigation() deliberately does not
 * filter on isActive, so an inactive row resolves routes while staying out of
 * the sidebar. Same mechanism as issue_detail, risk_detail and asset_detail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEntityResolver implements WorkflowEntityResolver {

    private final IncidentRepository incidentRepository;

    @Override
    public String entityType() { return "INCIDENT"; }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        return instance.getEntityId();
    }

    /**
     * Backs ENTITY_OWNER actor resolution. Null when nobody owns the incident
     * yet, which is normal in the first minutes — the engine then falls back to
     * PREVIOUS_ACTOR rather than stalling.
     */
    @Override
    public Long resolveOwnerId(WorkflowInstance instance) {
        return incidentRepository.findById(instance.getEntityId())
                .map(Incident::getOwnerId)
                .orElse(null);
    }

    @Override
    public String resolveEntityTitle(WorkflowInstance instance) {
        return incidentRepository.findById(instance.getEntityId())
                .map(this::label)
                .orElse(null);
    }

    /**
     * One query for the whole inbox instead of one findById per instance. The
     * single-title version is N sequential round trips on a large inbox, which
     * is the defect that made /my-tasks a 38-second call before this batch
     * variant was added to the SPI.
     */
    @Override
    public Map<Long, String> resolveEntityTitles(Collection<WorkflowInstance> instances) {
        Set<Long> ids = instances.stream()
                .map(WorkflowInstance::getEntityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();

        Map<Long, String> byIncidentId = new HashMap<>();
        incidentRepository.findAllById(ids)
                .forEach(i -> byIncidentId.put(i.getId(), label(i)));

        Map<Long, String> out = new HashMap<>();
        for (WorkflowInstance wi : instances) {
            String title = byIncidentId.get(wi.getEntityId());
            if (title != null) out.put(wi.getId(), title);
        }
        return out;
    }

    /** "INC-2026-0004 — Ransomware on the reporting server [CRITICAL]" */
    private String label(Incident inc) {
        String ref = inc.getIncidentRef();
        String base = (ref == null || ref.isBlank()) ? inc.getTitle() : ref + " — " + inc.getTitle();
        // Severity goes in the task title because an incident task competes for
        // attention with everything else in the inbox, and CRITICAL is the
        // reason to open it first.
        return inc.getSeverity() != null ? base + " [" + inc.getSeverity() + "]" : base;
    }
}
