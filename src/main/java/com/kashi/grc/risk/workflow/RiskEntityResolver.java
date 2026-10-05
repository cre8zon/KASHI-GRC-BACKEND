package com.kashi.grc.risk.workflow;

import com.kashi.grc.risk.domain.Risk;
import com.kashi.grc.risk.repository.RiskRepository;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves the artifact for RISK workflow instances.
 *
 * WorkflowInstance.entityId IS the risk id — RiskService sets it directly on
 * startWorkflow, so there is no indirection to unwind.
 *
 * Spring discovers this through @Component plus WorkflowEntityResolverRegistry;
 * nothing in WorkflowEngineService or TaskInbox needs changing.
 *
 * ── ROUTING ───────────────────────────────────────────────────────────────
 * TaskInbox.resolveTaskRoute looks the step's navKey up in ui_navigation and
 * uses that row's route. risk_register_patch.sql seeds a risk_detail nav row
 * (is_active = 0, route /module/risk/:id) for exactly this — getNavigation
 * deliberately does NOT filter on isActive, so an inactive row still resolves
 * routes while staying out of the sidebar. That mirrors the issue_detail row.
 *
 * The patch also adds RISK to TaskInbox's ENTITY_ROUTES fallback, so a step
 * saved without a navKey still routes rather than showing "contact admin".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskEntityResolver implements WorkflowEntityResolver {

    private final RiskRepository riskRepository;

    @Override
    public String entityType() { return "RISK"; }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        // entityId IS the riskId — no DB call needed.
        return instance.getEntityId();
    }

    /**
     * Backs ENTITY_OWNER actor resolution. Null when the risk has no owner yet,
     * which is the normal state for a freshly adopted library risk — the engine
     * then falls back to PREVIOUS_ACTOR.
     */
    @Override
    public Long resolveOwnerId(WorkflowInstance instance) {
        return riskRepository.findById(instance.getEntityId())
                .map(Risk::getOwnerId)
                .orElse(null);
    }

    @Override
    public String resolveEntityTitle(WorkflowInstance instance) {
        return riskRepository.findById(instance.getEntityId())
                .map(this::label)
                .orElse(null);
    }

    /**
     * One query for the whole inbox instead of one findById per instance.
     * The single-title version is N sequential round trips on a large inbox —
     * the defect that made /my-tasks a 38-second call before the batch variant
     * was added to this SPI.
     */
    @Override
    public Map<Long, String> resolveEntityTitles(Collection<WorkflowInstance> instances) {
        Set<Long> riskIds = instances.stream()
                .map(WorkflowInstance::getEntityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (riskIds.isEmpty()) return Map.of();

        Map<Long, String> titleByRiskId = new HashMap<>();
        riskRepository.findAllById(riskIds)
                .forEach(r -> titleByRiskId.put(r.getId(), label(r)));

        Map<Long, String> out = new HashMap<>();
        for (WorkflowInstance wi : instances) {
            String title = titleByRiskId.get(wi.getEntityId());
            if (title != null) out.put(wi.getId(), title);
        }
        return out;
    }

    /** "RSK-2026-0007 — Orphaned accounts after employee exit" */
    private String label(Risk risk) {
        String ref = risk.getRiskRef();
        return (ref == null || ref.isBlank()) ? risk.getTitle() : ref + " — " + risk.getTitle();
    }
}
