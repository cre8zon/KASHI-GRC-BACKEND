package com.kashi.grc.workflow.spi;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Auto-collects all WorkflowActorResolver beans registered in the Spring context.
 *
 * Adding a new module = implement WorkflowActorResolver + annotate @Component.
 * This registry picks it up automatically at startup. No configuration needed.
 *
 * Used by WorkflowEngineService.assignTasksForStep() when actorResolution = ASSIGNMENT_SCOPED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowActorResolverRegistry {

    private final List<WorkflowActorResolver> resolvers;
    private Map<String, WorkflowActorResolver> resolverMap;

    @PostConstruct
    public void init() {
        resolverMap = resolvers.stream()
                .collect(Collectors.toMap(WorkflowActorResolver::entityType, r -> r));
        log.info("[ACTOR-RESOLVER] Registered {} resolver(s): {}",
                resolverMap.size(), resolverMap.keySet());
    }

    /**
     * Resolves actor user IDs for the given workflow instance and step.
     * Returns empty list if no resolver is registered for this entityType,
     * causing the engine to fall back to ROLE_BASED resolution.
     */
    public List<Long> resolve(WorkflowInstance instance, StepInstance si) {
        WorkflowActorResolver resolver = resolverMap.get(instance.getEntityType());
        if (resolver == null) {
            log.debug("[ACTOR-RESOLVER] No resolver for entityType='{}' — ROLE_BASED fallback",
                    instance.getEntityType());
            return List.of();
        }
        List<Long> ids = resolver.resolveActorIds(instance, si);
        log.info("[ACTOR-RESOLVER] entityType='{}' | step='{}' | resolved {} actor(s)",
                instance.getEntityType(), si.getSnapName(), ids.size());
        return ids;
    }
}