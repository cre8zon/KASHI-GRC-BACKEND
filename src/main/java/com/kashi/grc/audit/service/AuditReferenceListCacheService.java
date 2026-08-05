package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditTemplate;
import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.workflow.domain.Workflow;
import com.kashi.grc.workflow.dto.response.WorkflowResponse;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caches the two dropdown/search listings hit on every "New engagement"
 * modal open — templates (by frameworkRef) and workflow blueprints (by
 * entityType). Both are read-heavy, low-churn: they only change when an
 * admin publishes a template or edits a workflow blueprint, but every user
 * re-triggers the same query the instant they open the create-engagement
 * form.
 *
 * DELIBERATELY A SEPARATE SERVICE, NOT A METHOD ON THE CONTROLLER:
 * @Cacheable is proxy-based, same as @Transactional — calling a @Cacheable
 * method via `this.method()` from within the same class bypasses the Spring
 * AOP proxy entirely and silently skips the cache on every call, with no
 * error to signal it. Putting it here means the controller calls through
 * the real bean reference, so the proxy — and therefore the cache — is
 * actually in the call path.
 *
 * NO EXPLICIT key= ON @Cacheable: relying on Spring's default
 * SimpleKeyGenerator, which composes a key from every method parameter
 * (isSystem, tenantId, the params map, take/skip) via their own
 * equals()/hashCode(). Hand-writing a SpEL key string here would be more
 * error-prone than just letting Spring do what it already does correctly
 * for exactly this "cache by all my arguments" case.
 *
 * INVALIDATION: TTL-only (15 min, see CacheConfig) — same trade-off
 * AssessmentTemplateStructureCacheService already makes: publishing a
 * template being invisible in this dropdown for up to 15 minutes is an
 * acceptable cost for not having to wire eviction into every admin
 * template/workflow CRUD path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditReferenceListCacheService {

    private final DbRepository dbRepository;
    private final WorkflowEngineService workflowEngineService;

    @Cacheable(cacheNames = CacheNames.AUDIT_TEMPLATE_LIST)
    public PaginatedResponse<Map<String, Object>> listTemplates(
            boolean isSystem, Long tenantId, PageDetails pageDetails, Map<String, String> allParams) {

        log.debug("[AUDIT-REF-CACHE] Building templates list from MySQL | isSystem={} tenantId={}",
                isSystem, tenantId);

        return dbRepository.findAll(
                AuditTemplate.class,
                pageDetails,
                (cb, root) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    if (!isSystem) {
                        predicates.add(cb.equal(root.get("status"), "PUBLISHED"));
                        predicates.add(cb.or(
                                cb.isNull(root.get("tenantId")),
                                cb.equal(root.get("tenantId"), tenantId)
                        ));
                    }
                    if (allParams.containsKey("status"))
                        predicates.add(cb.equal(root.get("status"),
                                allParams.get("status").toUpperCase()));
                    if (allParams.containsKey("audittype"))
                        predicates.add(cb.equal(root.get("auditType"),
                                AuditTemplate.AuditType.valueOf(allParams.get("audittype").toUpperCase())));
                    if (allParams.containsKey("frameworkref")) {
                        String want = allParams.get("frameworkref")
                                .replaceAll("\\s+", "").toLowerCase();
                        jakarta.persistence.criteria.Expression<String> normalized =
                                cb.lower(cb.function("REPLACE", String.class,
                                        root.get("frameworkRef"), cb.literal(" "), cb.literal("")));
                        predicates.add(cb.equal(normalized, want));
                    }
                    return predicates;
                },
                (cb, root) -> Map.of("name", root.get("name"), "status", root.get("status")),
                this::buildTemplateMap
        );
    }

    @Cacheable(cacheNames = CacheNames.WORKFLOW_BLUEPRINT_LIST)
    public PaginatedResponse<WorkflowResponse> listWorkflowBlueprints(
            boolean isSystem, PageDetails pageDetails, String entityType) {

        log.debug("[AUDIT-REF-CACHE] Building workflow blueprint list from MySQL | isSystem={} entityType={}",
                isSystem, entityType);

        PaginatedResponse<Workflow> raw = dbRepository.findAll(
                Workflow.class,
                pageDetails,
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    preds.add(cb.isNull(root.get("tenantId")));
                    if (!isSystem) preds.add(cb.isTrue(root.get("isActive")));
                    if (entityType != null)
                        preds.add(cb.equal(root.get("entityType"), entityType));
                    return preds;
                },
                (cb, root) -> Map.of("name", root.get("name"), "entitytype", root.get("entityType")),
                w -> w
        );

        // Reuses the existing full response builder as-is — same shape the
        // frontend already gets today, just cached now instead of rebuilt
        // (and its own 6 queries re-run) on every dropdown open.
        List<WorkflowResponse> responses = workflowEngineService.buildWorkflowResponsesBulk(raw.getItems());
        return new PaginatedResponse<>(responses, raw.getPagination().getTotalItems(), pageDetails);
    }

    private Map<String, Object> buildTemplateMap(AuditTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           t.getId());
        String displayName = t.getTemplateName() != null ? t.getTemplateName() : t.getName();
        m.put("name",         displayName);
        m.put("templateName", displayName);
        m.put("description",  t.getDescription());
        m.put("frameworkRef", t.getFrameworkRef());
        m.put("auditType",    t.getAuditType() != null ? t.getAuditType().name() : null);
        m.put("version",      t.getVersion());
        m.put("status",       t.getStatus());
        m.put("publishedAt",  t.getPublishedAt());
        m.put("tenantId",     t.getTenantId());
        m.put("createdAt",    t.getCreatedAt());
        return m;
    }
}