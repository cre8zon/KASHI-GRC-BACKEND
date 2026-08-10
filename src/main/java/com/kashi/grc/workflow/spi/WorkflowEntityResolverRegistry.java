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

    /**
     * Step-and-user-aware artifact resolution.
     */
    public Long resolveArtifactId(WorkflowInstance instance, StepInstance stepInstance, Long assignedUserId) {
        if (instance == null || instance.getEntityType() == null) return null;
        WorkflowEntityResolver resolver = byEntityType.get(instance.getEntityType());
        if (resolver == null) return null;
        try {
            return resolver.resolveArtifactId(instance, stepInstance, assignedUserId);
        } catch (Exception e) {
            log.warn("[ENTITY-RESOLVER] User-aware resolver threw for instanceId={}: {}",
                    instance.getId(), e.getMessage());
            return null;
        }
    }

    /**
     if (instance == null || instance.getEntityType() == null) return null;
     WorkflowEntityResolver resolver = byEntityType.get(instance.getEntityType());
     if (resolver == null) return null;
     try {
     Long artifactId = resolver.resolveArtifactId(instance, stepInstance);
     log.debug("[ENTITY-RESOLVER] Step-aware artifactId={} for entityType='{}' step='{}'",
     artifactId, instance.getEntityType(),
     stepInstance != null ? stepInstance.getSnapName() : "null");
     return artifactId;
     } catch (Exception e) {
     log.warn("[ENTITY-RESOLVER] Step-aware resolver threw for instanceId={}: {}",
     instance.getId(), e.getMessage());
     return null;
     }
     }

     /**
     * Resolves the owner user ID for the entity linked to a workflow instance.
     * Delegates to the registered resolver for the instance's entityType.
     * Returns null if no resolver exists or resolver returns null.
     * Used by WorkflowEngineService when actorResolution = ENTITY_OWNER.
     */
    public Long resolveOwnerId(WorkflowInstance instance) {
        if (instance == null || instance.getEntityType() == null) return null;
        WorkflowEntityResolver resolver = byEntityType.get(instance.getEntityType());
        if (resolver == null) {
            log.debug("[ENTITY-RESOLVER] No resolver for entityType='{}' — ownerId will be null",
                    instance.getEntityType());
            return null;
        }
        try {
            Long ownerId = resolver.resolveOwnerId(instance);
            log.debug("[ENTITY-RESOLVER] Resolved ownerId={} for entityType='{}' instanceId={}",
                    ownerId, instance.getEntityType(), instance.getId());
            return ownerId;
        } catch (Exception e) {
            log.warn("[ENTITY-RESOLVER] resolveOwnerId threw for instanceId={} — returning null: {}",
                    instance.getId(), e.getMessage());
            return null;
        }
    }

    /** True if a resolver is registered for this entityType */
    public boolean supports(String entityType) {
        return byEntityType.containsKey(entityType);
    }
    /**
     * Bulk title resolution for one entity type. Returns an empty map when no
     * resolver is registered, matching the single-instance behaviour of returning
     * null rather than throwing.
     */
    public java.util.Map<Long, String> resolveEntityTitles(
            String entityType, java.util.Collection<WorkflowInstance> instances) {
        if (instances == null || instances.isEmpty()) return java.util.Map.of();
        WorkflowEntityResolver resolver = byEntityType.get(entityType);
        if (resolver == null) return java.util.Map.of();
        try {
            return resolver.resolveEntityTitles(instances);
        } catch (Exception e) {
            log.warn("[ENTITY-RESOLVER] resolveEntityTitles threw for entityType={}: {}",
                    entityType, e.getMessage());
            return java.util.Map.of();
        }
    }

    public String resolveEntityTitle(WorkflowInstance instance) {
        if (instance == null || instance.getEntityType() == null) return null;
        WorkflowEntityResolver resolver = byEntityType.get(instance.getEntityType());
        if (resolver == null) return null;
        try { return resolver.resolveEntityTitle(instance); }
        catch (Exception e) {
            log.warn("[ENTITY-RESOLVER] resolveEntityTitle threw for instanceId={}: {}", instance.getId(), e.getMessage());
            return null;
        }
    }

}