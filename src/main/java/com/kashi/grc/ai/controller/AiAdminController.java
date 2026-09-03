package com.kashi.grc.ai.controller;

import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiPromptTemplate;
import com.kashi.grc.ai.eval.AiEvalHarness;
import com.kashi.grc.ai.prompt.PromptRegistry;
import com.kashi.grc.ai.provider.LlmProviderRegistry;
import com.kashi.grc.ai.policy.PolicyVariableCatalog;
import com.kashi.grc.ai.rag.IngestionAsyncFacade;
import com.kashi.grc.ai.rag.PolicyCorpusReconciler;
import com.kashi.grc.ai.rag.IngestionService;
import com.kashi.grc.ai.rag.RetrievalService;
import com.kashi.grc.ai.repository.AiInteractionRepository;
import com.kashi.grc.ai.usage.AiUsageService;
import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.repository.AuditPolicyRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator surface: prompts, corpus, usage, evals, health.
 *
 * ── SECURE THIS PROPERLY ─────────────────────────────────────────────────────
 * Prompt editing is behaviour editing for every generation in the system. These
 * routes belong behind a platform-admin permission in your existing RBAC — add
 * @PreAuthorize with the right authority once you have picked the permission
 * code, rather than relying on the path prefix.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AiAdminController {

    private final PromptRegistry          promptRegistry;
    private final IngestionAsyncFacade    ingestionAsync;
    private final PolicyCorpusReconciler  corpusReconciler;
    private final RetrievalService        retrievalService;
    private final AiUsageService          usageService;
    private final AiEvalHarness           evalHarness;
    private final LlmProviderRegistry     providerRegistry;
    private final AiInteractionRepository interactionRepository;
    private final AuditPolicyRepository   policyRepository;
    private final UtilityService          utilityService;

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/v1/ai/admin/health")
    public ApiResponse<Map<String, Object>> health() {
        User user = utilityService.getLoggedInDataContext();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providers", providerRegistry.configuredKeys());
        m.put("corpus", retrievalService.corpusStats(user.getTenantId()));
        m.put("usage", usageService.summary(user.getTenantId()));
        return ApiResponse.success(m);
    }

    // ── Prompts ───────────────────────────────────────────────────────────────

    @GetMapping("/v1/ai/admin/prompts")
    public ApiResponse<List<AiPromptTemplate>> listPrompts() {
        return ApiResponse.success(promptRegistry.listActive());
    }

    @GetMapping("/v1/ai/admin/prompts/{key}/history")
    public ApiResponse<List<AiPromptTemplate>> promptHistory(@PathVariable String key) {
        return ApiResponse.success(promptRegistry.history(key));
    }

    /**
     * Publish a new version. Append-only — the previous version is deactivated,
     * never mutated, so every past generation stays explainable.
     *
     * `scope=tenant` creates an override for the caller's organisation only,
     * which is how an enterprise customer gets house tone without forking.
     */
    @PostMapping("/v1/ai/admin/prompts")
    public ApiResponse<AiPromptTemplate> publishPrompt(@RequestBody AiPromptTemplate edited,
                                                       @RequestParam(defaultValue = "global") String scope,
                                                       @RequestParam(required = false) String changeNote) {
        User user = utilityService.getLoggedInDataContext();
        Long targetTenant = "tenant".equalsIgnoreCase(scope) ? user.getTenantId() : null;
        return ApiResponse.success(promptRegistry.publishNewVersion(
                edited, targetTenant, user.getId(),
                changeNote == null ? "Updated via admin console" : changeNote));
    }

    // ── Corpus ────────────────────────────────────────────────────────────────

    @GetMapping("/v1/ai/admin/corpus")
    public ApiResponse<Map<String, Object>> corpus() {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(retrievalService.corpusStats(user.getTenantId()));
    }

    /**
     * Bulk-index the policy library.
     *
     * Run once after enabling AI, then rely on the save hook. Unchanged policies
     * short-circuit on the content hash, so re-running is cheap and safe — which
     * is what makes this fit as a nightly sweep later.
     */
    @PostMapping("/v1/ai/admin/corpus/ingest-policies")
    public ApiResponse<Map<String, Object>> ingestPolicies() {
        User user = utilityService.getLoggedInDataContext();
        String batchId = UUID.randomUUID().toString();

        List<AuditPolicy> policies = policyRepository.findAll().stream()
                .filter(p -> p.getTenantId() == null || p.getTenantId().equals(user.getTenantId()))
                .filter(p -> p.getContentBody() != null && !p.getContentBody().isBlank())
                .toList();

        for (AuditPolicy p : policies) {
            ingestionAsync.ingest(new IngestionService.IngestRequest(
                    p.getTenantId() == null ? ChunkSourceType.POLICY_TEMPLATE : ChunkSourceType.POLICY,
                    p.getId(),
                    (p.getPolicyRef() == null ? "" : p.getPolicyRef() + " ") + p.getTitle()
                            + (p.getVersion() == null ? "" : " v" + p.getVersion()),
                    p.getContentBody(), true,
                    p.getTenantId(), user.getId(), batchId,
                    Map.of("status", String.valueOf(p.getStatus()),
                            "frameworkRefs", String.valueOf(p.getFrameworkRefs()),
                            "controlTags", String.valueOf(p.getControlTags()))));
        }

        log.info("[AI-ADMIN] queued {} policies for ingestion | batch={}", policies.size(), batchId);
        return ApiResponse.success(Map.of("queued", policies.size(), "batchId", batchId));
    }

    /**
     * Force the corpus sweep now.
     *
     * The scheduled hourly run covers write paths that bypass PolicyCorpusHook —
     * notably AuditPolicyBulkAdoptService, which saves through the repository
     * directly. Call this after a bulk adoption if the tenant wants to draft
     * immediately rather than wait for the next sweep.
     */
    @PostMapping("/v1/ai/admin/corpus/reconcile")
    public ApiResponse<Map<String, Object>> reconcileCorpus() {
        return ApiResponse.success(corpusReconciler.reconcile());
    }

    /**
     * The policy variable catalogue, grouped for a UI picker.
     *
     * Worth exposing rather than hardcoding in the frontend: the same list drives
     * the resolver and the generation prompt, and three copies of it drift
     * silently — the model keeps emitting a variable the resolver stopped
     * handling and nobody notices until a document ships with raw mustache.
     */
    @GetMapping("/v1/ai/admin/policy-variables")
    public ApiResponse<Map<String, Object>> policyVariables() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (PolicyVariableCatalog.Variable v : PolicyVariableCatalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key",      v.getKey());
            m.put("label",    v.getLabel());
            m.put("source",   v.getSource().name());
            m.put("example",  v.getExample());
            m.put("guidance", v.getGuidance());
            // Tells the UI which syntax to insert: {{x}} for workflow-filled,
            // the literal value for known facts, [[X]] for undecided parameters.
            m.put("syntax", v.isWorkflow() ? "{{" + v.getKey() + "}}"
                    : v.isParameter() ? "[[" + v.getKey().replace('_',' ').toUpperCase() + "]]"
                      : "literal value");
            grouped.computeIfAbsent(v.getCategory(), k -> new ArrayList<>()).add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", grouped);
        out.put("workflowFilled", PolicyVariableCatalog.workflowKeys());
        return ApiResponse.success(out);
    }

    /** Re-embed everything built with a stale model or dimension. */
    @PostMapping("/v1/ai/admin/corpus/reindex")
    public ApiResponse<Map<String, Object>> reindex() {
        ingestionAsync.reindexStale();
        return ApiResponse.success(Map.of("started", true,
                "note", "Running on the aiTaskExecutor pool — watch the logs for progress"));
    }

    // ── Usage ─────────────────────────────────────────────────────────────────

    @GetMapping("/v1/ai/admin/usage")
    public ApiResponse<Map<String, Object>> usage() {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(usageService.summary(user.getTenantId()));
    }

    @PostMapping("/v1/ai/admin/usage/reconcile")
    public ApiResponse<Map<String, Object>> reconcile(@RequestParam(required = false) String period) {
        User user = utilityService.getLoggedInDataContext();
        String p = period == null ? AiUsageService.currentPeriod() : period;
        long actual = usageService.reconcile(user.getTenantId(), p);
        return ApiResponse.success(Map.of("period", p, "ledgerTokens", actual));
    }

    @GetMapping("/v1/ai/admin/usage/by-task")
    public ApiResponse<List<Map<String, Object>>> usageByTask(@RequestParam(defaultValue = "30") int days) {
        User user = utilityService.getLoggedInDataContext();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : interactionRepository.statsByTaskType(user.getTenantId(), LocalDateTime.now().minusDays(days))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskType",  String.valueOf(r[0]));
            m.put("calls",     ((Number) r[1]).longValue());
            m.put("tokens",    ((Number) r[2]).longValue());
            m.put("avgLatencyMs", Math.round(((Number) r[3]).doubleValue()));
            out.add(m);
        }
        return ApiResponse.success(out);
    }

    // ── Provenance ────────────────────────────────────────────────────────────

    /**
     * Every model call behind one user action, in order.
     *
     * This backs the "how was this generated" panel — the answer to an auditor
     * asking on what basis a clause was written, and the reason ai_interactions
     * carries a correlation id at all.
     */
    @GetMapping("/v1/ai/admin/trace/{correlationId}")
    public ApiResponse<List<Map<String, Object>>> trace(@PathVariable String correlationId) {
        User user = utilityService.getLoggedInDataContext();
        List<Map<String, Object>> out = new ArrayList<>();

        interactionRepository.findByCorrelationIdOrderByStepIndexAsc(correlationId).stream()
                .filter(i -> i.getTenantId() == null || i.getTenantId().equals(user.getTenantId()))
                .forEach(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i.getId());
                    m.put("step", i.getStepName());
                    m.put("taskType", i.getTaskType());
                    m.put("promptTemplate", i.getPromptTemplateKey() + " v" + i.getPromptTemplateVersion());
                    m.put("provider", i.getProviderKey() != null ? i.getProviderKey() : String.valueOf(i.getProvider()));
                    m.put("model", i.getModel());
                    m.put("tokens", i.getTotalTokens());
                    m.put("latencyMs", i.getLatencyMs());
                    m.put("status", i.getStatus());
                    m.put("guardrails", i.getGuardrailsTriggered());
                    m.put("sourceChunkIds", i.getRetrievedChunkIds());
                    m.put("at", i.getCreatedAt());
                    out.add(m);
                });
        return ApiResponse.success(out);
    }

    // ── Evals ─────────────────────────────────────────────────────────────────

    /** Run the golden set. Wire this into CI and gate prompt merges on the pass rate. */
    @PostMapping("/v1/ai/admin/eval/run")
    public ApiResponse<Map<String, Object>> runEvals() {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(evalHarness.runGoldenSet(user.getTenantId()));
    }
}