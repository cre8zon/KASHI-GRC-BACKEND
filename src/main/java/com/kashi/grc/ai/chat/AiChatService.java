package com.kashi.grc.ai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiEnums.InteractionStatus;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.domain.AiInteraction;
import com.kashi.grc.ai.domain.AiOrgProfile;
import com.kashi.grc.ai.domain.AiPromptTemplate;
import com.kashi.grc.ai.guardrail.GuardrailException;
import com.kashi.grc.ai.guardrail.JsonSchemaGuard;
import com.kashi.grc.ai.guardrail.PiiRedactor;
import com.kashi.grc.ai.prompt.PromptRegistry;
import com.kashi.grc.ai.prompt.PromptRenderer;
import com.kashi.grc.ai.provider.LlmDtos.LlmMessage;
import com.kashi.grc.ai.provider.LlmDtos.LlmRequest;
import com.kashi.grc.ai.provider.LlmDtos.LlmResponse;
import com.kashi.grc.ai.provider.LlmProvider;
import com.kashi.grc.ai.provider.LlmProviderRegistry;
import com.kashi.grc.ai.repository.AiInteractionRepository;
import com.kashi.grc.ai.repository.AiOrgProfileRepository;
import com.kashi.grc.ai.usage.AiUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The single door to every language model in the platform.
 *
 * ── WHY EVERYTHING GOES THROUGH ONE CLASS ────────────────────────────────────
 * Because the guarantees this module makes are only guarantees if they cannot be
 * bypassed. If any service can construct an LlmRequest and call a provider
 * directly, then "every generation is logged", "no tenant exceeds its budget"
 * and "personal data is tokenised" all become conventions that hold until
 * somebody is in a hurry. Making the providers useless without this class turns
 * those from conventions into properties.
 *
 * ── WHAT HAPPENS ON EVERY CALL, IN ORDER ─────────────────────────────────────
 *   1. tenant AI enabled?                        -> BLOCKED
 *   2. budget available?                         -> BLOCKED
 *   3. resolve prompt template (tenant->global)
 *   4. render variables (missing = hard failure)
 *   5. redact personal identifiers
 *   6. prompt size check                         -> BLOCKED
 *   7. resolve provider and model
 *   8. cache probe on input hash
 *   9. call, with retry and backoff
 *  10. rehydrate redacted values
 *  11. validate JSON against schema, repair once
 *  12. write the interaction row
 *  13. record usage
 *
 * Steps 12 and 13 run even when the call fails. A failed call still cost money
 * and still needs explaining.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final LlmProviderRegistry     providerRegistry;
    private final PromptRegistry          promptRegistry;
    private final PromptRenderer          renderer;
    private final PiiRedactor             redactor;
    private final JsonSchemaGuard         jsonGuard;
    private final AiUsageService          usageService;
    private final AiInteractionRepository interactionRepository;
    private final AiOrgProfileRepository  orgProfileRepository;
    private final AiInteractionRecorder   recorder;
    private final AiProperties            props;
    private final ObjectMapper            mapper;

    // ── Call description ──────────────────────────────────────────────────────

    /**
     * Everything one call needs. A builder rather than a long parameter list
     * because most calls set four of these and the rest are pipeline plumbing.
     */
    public static class AiCall {
        private String  templateKey;
        private TaskType taskType;
        private Map<String, Object> variables = new LinkedHashMap<>();
        private Long    tenantId;
        private Long    userId;
        private String  correlationId;
        private Long    parentInteractionId;
        private Integer stepIndex;
        private String  stepName;
        private String  pipelineName;
        private String  entityType;
        private Long    entityId;
        private List<Long> retrievedChunkIds;
        private String  providerOverride;
        private String  modelOverride;
        private boolean allowCache = false;
        private boolean evalRun    = false;
        /** Extra context appended after the rendered template — the retrieved block. */
        private String  contextBlock;

        public static AiCall of(String templateKey, TaskType taskType) {
            AiCall c = new AiCall(); c.templateKey = templateKey; c.taskType = taskType; return c;
        }
        public AiCall var(String k, Object v)          { this.variables.put(k, v); return this; }
        public AiCall vars(Map<String, Object> v)      { this.variables.putAll(v); return this; }
        public AiCall tenant(Long t)                   { this.tenantId = t; return this; }
        public AiCall user(Long u)                     { this.userId = u; return this; }
        public AiCall correlation(String c)            { this.correlationId = c; return this; }
        public AiCall parent(Long p)                   { this.parentInteractionId = p; return this; }
        public AiCall step(int i, String name)         { this.stepIndex = i; this.stepName = name; return this; }
        public AiCall pipeline(String p)               { this.pipelineName = p; return this; }
        public AiCall entity(String type, Long id)     { this.entityType = type; this.entityId = id; return this; }
        public AiCall chunks(List<Long> ids)           { this.retrievedChunkIds = ids; return this; }
        public AiCall context(String block)            { this.contextBlock = block; return this; }
        public AiCall provider(String p)               { this.providerOverride = p; return this; }
        public AiCall model(String m)                  { this.modelOverride = m; return this; }
        public AiCall cacheable(boolean b)             { this.allowCache = b; return this; }
        public AiCall eval(boolean b)                  { this.evalRun = b; return this; }

        public Long getTenantId() { return tenantId; }
        public String getCorrelationId() { return correlationId; }
    }

    /** Result plus the interaction id, so the caller can attach feedback to it. */
    public record AiResult(String content, JsonNode json, Long interactionId,
                           String model, int totalTokens, long latencyMs, boolean fromCache) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public AiResult complete(AiCall call) {
        return execute(call, null);
    }

    /**
     * Structured call. Forces JSON mode, validates against the template's schema
     * and repairs once on failure.
     */
    public AiResult completeJson(AiCall call) {
        AiResult r = execute(call, null);
        if (r.json() == null) {
            throw GuardrailException.invalidOutput("expected structured output but none could be parsed");
        }
        return r;
    }

    /** Streaming. onToken fires per delta. Not retried — see LlmProviderRegistry. */
    public AiResult stream(AiCall call, Consumer<String> onToken) {
        return execute(call, onToken);
    }

    // ── The one implementation ────────────────────────────────────────────────

    private AiResult execute(AiCall call, Consumer<String> onToken) {
        long started = System.currentTimeMillis();

        if (!props.isEnabled()) {
            throw new GuardrailException("AI_DISABLED",
                    "AI features are not enabled on this deployment",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }

        AiOrgProfile profile = call.tenantId == null ? null
                : orgProfileRepository.findByTenantId(call.tenantId).orElse(null);

        // 1 ── tenant opt-out
        if (profile != null && !Boolean.TRUE.equals(profile.getAiEnabled())) {
            recorder.recordBlocked(call.taskType, call.templateKey, "AI_DISABLED_FOR_TENANT",
                    call.entityType, call.entityId, call.correlationId, call.userId, call.tenantId);
            throw GuardrailException.tenantDisabled();
        }

        // 2 ── budget
        try {
            usageService.assertWithinBudget(call.tenantId);
        } catch (GuardrailException e) {
            recorder.recordBlocked(call.taskType, call.templateKey, e.getErrorCode(),
                    call.entityType, call.entityId, call.correlationId, call.userId, call.tenantId);
            throw e;
        }

        // 3 ── prompt
        AiPromptTemplate template = promptRegistry.resolve(call.templateKey, call.tenantId);

        // 4 ── render; missing variables throw before any spend
        String system = renderer.render(template.getSystemPrompt(), call.variables);
        String user   = renderer.render(template.getUserPrompt(),  call.variables);
        if (call.contextBlock != null && !call.contextBlock.isBlank()) {
            user = user + "\n\n" + call.contextBlock;
        }

        // 5 ── redact
        PiiRedactor.Redaction redSystem = redactor.redact(system);
        PiiRedactor.Redaction redUser   = redactor.redact(user);
        Map<String, String> restore = new LinkedHashMap<>();
        restore.putAll(redSystem.restoreMap());
        restore.putAll(redUser.restoreMap());

        List<String> guardrails = new ArrayList<>();
        if (redSystem.anyRedacted() || redUser.anyRedacted()) guardrails.add("PII_REDACTED");

        // 6 ── size
        int totalChars = (redSystem.redactedText() == null ? 0 : redSystem.redactedText().length())
                + redUser.redactedText().length();
        if (totalChars > props.getGuardrail().getMaxPromptChars()) {
            recorder.recordBlocked(call.taskType, call.templateKey, "AI_PROMPT_TOO_LARGE",
                    call.entityType, call.entityId, correlationIdOf(call), call.userId, call.tenantId);
            throw GuardrailException.promptTooLarge(totalChars, props.getGuardrail().getMaxPromptChars());
        }

        // 7 ── provider and model
        LlmProvider provider = providerRegistry.resolve(
                call.providerOverride,
                profile != null ? profile.getPreferredProvider() : null);

        String model = firstNonBlank(
                call.modelOverride,
                template.getModelHint(),
                profile != null ? profile.getPreferredModel() : null,
                Boolean.TRUE.equals(template.getPreferFastModel())
                        ? provider.defaultFastModel() : provider.defaultChatModel());

        String correlationId = call.correlationId != null ? call.correlationId : UUID.randomUUID().toString();
        String inputVarsJson = writeJson(call.variables);
        String inputHash = com.kashi.grc.ai.rag.IngestionService.sha256(
                call.templateKey + "|" + template.getVersion() + "|" + model + "|" + inputVarsJson);

        // 8 ── cache probe. Only for deterministic, non-streaming calls.
        if (call.allowCache && onToken == null) {
            var cached = interactionRepository.findCacheable(
                    inputHash, call.tenantId, java.time.LocalDateTime.now().minusDays(7),
                    com.kashi.grc.ai.domain.AiEnums.InteractionStatus.SUCCESS);
            if (!cached.isEmpty()) {
                AiInteraction hit = cached.get(0);
                log.debug("[AI-CHAT] cache hit for {} (interaction {})", call.templateKey, hit.getId());
                return new AiResult(hit.getRawResponse(), parseQuietly(hit.getRawResponse()),
                        hit.getId(), hit.getModel(), 0, System.currentTimeMillis() - started, true);
            }
        }

        // 9 ── call
        LlmRequest request = new LlmRequest()
                .model(model)
                .temperature(template.getTemperature() != null ? template.getTemperature() : defaultTemperature(provider))
                .maxOutputTokens(template.getMaxOutputTokens() != null ? template.getMaxOutputTokens() : defaultMaxTokens(provider))
                .jsonMode(Boolean.TRUE.equals(template.getExpectsJson()));

        if (redSystem.redactedText() != null && !redSystem.redactedText().isBlank()) {
            request.addMessage(LlmMessage.system(redSystem.redactedText()));
        }
        request.addMessage(LlmMessage.user(redUser.redactedText()));

        LlmResponse response;
        try {
            response = onToken == null
                    ? providerRegistry.completeWithRetry(provider, request)
                    : providerRegistry.streamOrFallback(provider, request, onToken);
        } catch (RuntimeException e) {
            Long id = recorder.recordFailure(new AiInteractionRecorder.FailureRecord(
                    call.taskType, call.pipelineName, call.stepIndex, call.stepName,
                    template.getTemplateKey(), template.getVersion(), provider.type(), provider.key(), model,
                    redUser.redactedText(), inputVarsJson, inputHash,
                    call.entityType, call.entityId, correlationId, call.parentInteractionId,
                    call.userId, call.tenantId, call.evalRun,
                    System.currentTimeMillis() - started), e);
            log.error("[AI-CHAT] {} failed | interaction={} | {}", call.templateKey, id, e.getMessage());
            throw e;
        }

        if (response.wasTruncated()) guardrails.add("OUTPUT_TRUNCATED");

        // 10 ── rehydrate
        String content = redactor.rehydrate(response.content(), restore);

        // 11 ── validate and repair
        JsonNode parsed = null;
        InteractionStatus status = InteractionStatus.SUCCESS;

        if (Boolean.TRUE.equals(template.getExpectsJson())) {
            var validation = jsonGuard.validate(content, template.getResponseSchema());

            if (!validation.valid() && props.getGuardrail().getMaxJsonRepairAttempts() > 0) {
                log.warn("[AI-CHAT] {} returned invalid JSON ({}), attempting repair",
                        call.templateKey, validation.errorSummary());
                guardrails.add("JSON_REPAIRED");

                LlmRequest repair = new LlmRequest()
                        .model(model).temperature(0.0)
                        .maxOutputTokens(request.getMaxOutputTokens()).jsonMode(true)
                        .addMessage(LlmMessage.user(redUser.redactedText()))
                        .addMessage(LlmMessage.assistant(response.content()))
                        .addMessage(LlmMessage.user(jsonGuard.buildRepairInstruction(
                                validation.errors(), template.getResponseSchema())));
                try {
                    LlmResponse repaired = providerRegistry.completeWithRetry(provider, repair);
                    var revalidated = jsonGuard.validate(repaired.content(), template.getResponseSchema());
                    if (revalidated.valid()) {
                        content = redactor.rehydrate(repaired.content(), restore);
                        parsed  = revalidated.parsed();
                        // The repair round-trip is billed too.
                        usageService.record(call.tenantId, nz(repaired.promptTokens()),
                                nz(repaired.completionTokens()), 0,
                                usageService.computeCostMicros(provider.key(),
                                        nz(repaired.promptTokens()), nz(repaired.completionTokens())),
                                false, false);
                    } else {
                        status = InteractionStatus.INVALID_OUTPUT;
                    }
                } catch (RuntimeException e) {
                    log.error("[AI-CHAT] repair attempt failed: {}", e.getMessage());
                    status = InteractionStatus.INVALID_OUTPUT;
                }
            } else if (validation.valid()) {
                parsed = validation.parsed();
            } else {
                status = InteractionStatus.INVALID_OUTPUT;
            }
        }

        // 12 ── log
        long latency = System.currentTimeMillis() - started;
        long cost = usageService.computeCostMicros(provider.key(),
                nz(response.promptTokens()), nz(response.completionTokens()));

        AiInteraction interaction = interactionRepository.save(AiInteraction.builder()
                .taskType(call.taskType)
                .pipelineName(call.pipelineName).stepIndex(call.stepIndex).stepName(call.stepName)
                .promptTemplateKey(template.getTemplateKey()).promptTemplateVersion(template.getVersion())
                .provider(provider.type()).providerKey(provider.key())
                .model(response.model() != null ? response.model() : model)
                .temperature(request.getTemperature())
                .renderedPrompt(redUser.redactedText())          // post-redaction, by design
                .inputVariables(inputVarsJson)
                .retrievedChunkIds(joinIds(call.retrievedChunkIds))
                .inputHash(inputHash)
                .rawResponse(content)
                .finishReason(response.finishReason())
                .promptTokens(response.promptTokens()).completionTokens(response.completionTokens())
                .totalTokens(response.totalTokens()).costMicros(cost)
                .latencyMs(latency).retryCount(response.retryCount())
                .status(status)
                .guardrailsTriggered(guardrails.isEmpty() ? null : String.join(",", guardrails))
                .entityType(call.entityType).entityId(call.entityId)
                .correlationId(correlationId).parentInteractionId(call.parentInteractionId)
                .triggeredByUserId(call.userId).evalRun(call.evalRun)
                .tenantId(call.tenantId)
                .build());

        // 13 ── meter
        usageService.record(call.tenantId, nz(response.promptTokens()), nz(response.completionTokens()),
                0, cost, status != InteractionStatus.SUCCESS, false);

        log.info("[AI-CHAT] {} | model={} tokens={} latency={}ms status={} interaction={}",
                call.templateKey, model, response.totalTokens(), latency, status, interaction.getId());

        if (status == InteractionStatus.INVALID_OUTPUT && Boolean.TRUE.equals(template.getExpectsJson())) {
            throw GuardrailException.invalidOutput("the model's structured response could not be validated");
        }

        return new AiResult(content, parsed, interaction.getId(),
                response.model(), response.totalTokens(), latency, false);
    }

    /** Correlation id may not be assigned yet when an early guardrail fires. */
    private static String correlationIdOf(AiCall call) {
        return call.correlationId != null ? call.correlationId : UUID.randomUUID().toString();
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private double defaultTemperature(LlmProvider p) {
        var cfg = providerRegistry.configFor(p);
        return cfg != null ? cfg.getTemperature() : 0.2;
    }

    private int defaultMaxTokens(LlmProvider p) {
        var cfg = providerRegistry.configFor(p);
        return cfg != null ? cfg.getMaxOutputTokens() : 4096;
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id); }
        return sb.toString();
    }

    private String writeJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    private JsonNode parseQuietly(String s) {
        try { return mapper.readTree(jsonGuard.extractJson(s)); } catch (Exception e) { return null; }
    }
}