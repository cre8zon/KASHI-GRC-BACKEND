package com.kashi.grc.workflow.spi;

import com.kashi.grc.workflow.domain.WorkflowInstance;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Auto-collects all WorkflowEntityResolver beans registered in the Spring context.
 *
 * Adding a new module = implement WorkflowEntityResolver + annotate @Component.
 * This registry picks it up automatically at startup. No configuration needed.
 *
 * Used by WorkflowEngineService.toEnrichedTaskResponse() to resolve artifactId
 * for any workflow instance regardless of its entityType.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEntityResolverRegistry {

    /** Spring injects ALL WorkflowEntityResolver beans automatically */
    private final List<WorkflowEntityResolver> resolvers;

    private Map<String, WorkflowEntityResolver> byEntityType;

    @PostConstruct
    public void init() {
        byEntityType = resolvers.stream()
                .collect(Collectors.toMap(
                        WorkflowEntityResolver::entityType,
                        r -> r,
                        (a, b) -> {
                            log.warn("[ENTITY-RESOLVER] Duplicate resolver for entityType '{}' — keeping {}",
                                    a.entityType(), a.getClass().getSimpleName());
                            return a;
                        }
                ));
        log.info("[ENTITY-RESOLVER] Registered {} resolver(s): {}",
                byEntityType.size(), byEntityType.keySet());
    }

    /**
     * Resolves the primary artifact ID for a workflow instance.
     * Returns null if no resolver exists for the instance's entityType,
     * or if the resolver returns null (artifact not yet created).
     */
    public Long resolveArtifactId(WorkflowInstance instance) {
        if (instance == null || instance.getEntityType() == null) return null;
        WorkflowEntityResolver resolver = byEntityType.get(instance.getEntityType());
        if (resolver == null) {
            log.debug("[ENTITY-RESOLVER] No resolver for entityType='{}' — artifactId will be null",
                    instance.getEntityType());
            return null;
        }
        try {
            Long artifactId = resolver.resolveArtifactId(instance);
            log.debug("[ENTITY-RESOLVER] Resolved artifactId={} for entityType='{}' instanceId={}",
                    artifactId, instance.getEntityType(), instance.getId());
            return artifactId;
        } catch (Exception e) {
            log.warn("[ENTITY-RESOLVER] Resolver '{}' threw for instanceId={} — returning null: {}",
                    resolver.getClass().getSimpleName(), instance.getId(), e.getMessage());
            return null;
        }
    }

    /** True if a resolver is registered for this entityType */
    public boolean supports(String entityType) {
        return byEntityType.containsKey(entityType);
    }
}