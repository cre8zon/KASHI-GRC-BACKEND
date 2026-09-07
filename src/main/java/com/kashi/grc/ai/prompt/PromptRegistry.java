package com.kashi.grc.ai.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.domain.AiPromptTemplate;
import com.kashi.grc.ai.repository.AiPromptTemplateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Resolves the active prompt for a key, tenant-first.
 *
 * ── SEED FROM CLASSPATH, SERVE FROM DATABASE ─────────────────────────────────
 * Prompts ship as JSON under classpath:ai/prompts/ and are imported as version 1
 * global rows at startup if the key is absent. That gives both of the properties
 * you want and which neither approach gives alone:
 *
 *   - git remains the reviewable source of truth for the shipped prompts, so a
 *     prompt change goes through code review like any other behaviour change
 *   - the running system reads from the database, so a compliance lead can tune
 *     wording through the admin screen without a deploy, and every tuned version
 *     is attributable
 *
 * Import is deliberately create-only. It never overwrites an existing key,
 * because doing so would silently discard a customer's tuning on every restart —
 * which is the kind of bug that destroys trust in the whole feature.
 *
 * ── CACHING ──────────────────────────────────────────────────────────────────
 * No cache here yet. Resolution is one indexed query on a table with tens of
 * rows, and prompts are edited rarely but must take effect immediately when they
 * are. If profiling ever shows this on a hot path, add a CacheNames region —
 * but do not add it speculatively, because a stale prompt is a confusing bug to
 * chase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptRegistry {

    private final AiPromptTemplateRepository repository;
    private final ObjectMapper mapper;

    @PostConstruct
    public void seedFromClasspath() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:ai/prompts/*.json");
            int imported = 0;
            for (Resource f : files) {
                try (var in = f.getInputStream()) {
                    JsonNode node = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (importIfAbsent(node)) imported++;
                } catch (Exception e) {
                    log.error("[AI-PROMPT] could not seed {}: {}", f.getFilename(), e.getMessage());
                }
            }
            log.info("[AI-PROMPT] seed complete | files={} imported={}", files.length, imported);
        } catch (Exception e) {
            log.warn("[AI-PROMPT] no seed prompts found on classpath: {}", e.getMessage());
        }
    }

    /*
     * Deliberately NOT @Transactional. It is called from @PostConstruct in this
     * same class, which is a self-invocation that would bypass the proxy and
     * make the annotation a decorative lie. Each seed is a single
     * repository.save() with its own transaction, which is all this needs.
     */
    private boolean importIfAbsent(JsonNode node) {
        String key = node.path("templateKey").asText(null);
        if (key == null) return false;
        if (repository.existsByTemplateKeyAndTenantId(key, null)) return false;   // never overwrite

        AiPromptTemplate t = AiPromptTemplate.builder()
                .templateKey(key)
                .version(1)
                .active(true)
                .taskType(TaskType.valueOf(node.path("taskType").asText()))
                .displayName(node.path("displayName").asText(key))
                .description(node.path("description").asText(null))
                .systemPrompt(node.path("systemPrompt").asText(null))
                .userPrompt(node.path("userPrompt").asText(""))
                .requiredVariables(node.path("requiredVariables").asText(null))
                .responseSchema(node.hasNonNull("responseSchema") ? node.get("responseSchema").toString() : null)
                .expectsJson(node.path("expectsJson").asBoolean(false))
                .modelHint(node.path("modelHint").asText(null))
                .temperature(node.hasNonNull("temperature") ? node.get("temperature").asDouble() : null)
                .maxOutputTokens(node.hasNonNull("maxOutputTokens") ? node.get("maxOutputTokens").asInt() : null)
                .preferFastModel(node.path("preferFastModel").asBoolean(false))
                .usesRetrieval(node.path("usesRetrieval").asBoolean(false))
                .retrievalSourceTypes(node.path("retrievalSourceTypes").asText(null))
                .retrievalTopK(node.hasNonNull("retrievalTopK") ? node.get("retrievalTopK").asInt() : null)
                .changeNote("Seeded from classpath at startup")
                .tenantId(null)
                .build();

        repository.save(t);
        log.info("[AI-PROMPT] seeded '{}' as global v1", key);
        return true;
    }

    /** Tenant override first, platform default second. Never returns null. */
    @Transactional(readOnly = true)
    public AiPromptTemplate resolve(String templateKey, Long tenantId) {
        List<AiPromptTemplate> found = repository.resolveActive(templateKey, tenantId);
        if (found.isEmpty()) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "AI_PROMPT_NOT_FOUND",
                    "No active prompt template for key '" + templateKey + "'",
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return found.get(0);
    }

    /**
     * Publish a new version. Append-only, exactly like AuditPolicy versioning:
     * the previous row is deactivated, never edited, so any interaction that
     * cited it stays explainable forever.
     */
    @Transactional
    public AiPromptTemplate publishNewVersion(AiPromptTemplate edited, Long tenantId, Long userId, String changeNote) {
        Integer max = repository.maxVersion(edited.getTemplateKey(), tenantId);
        int next = (max == null ? 0 : max) + 1;

        repository.resolveActive(edited.getTemplateKey(), tenantId).stream()
                .filter(t -> java.util.Objects.equals(t.getTenantId(), tenantId))
                .forEach(t -> { t.setActive(false); repository.save(t); });

        AiPromptTemplate fresh = AiPromptTemplate.builder()
                .templateKey(edited.getTemplateKey())
                .version(next)
                .active(true)
                .taskType(edited.getTaskType())
                .displayName(edited.getDisplayName())
                .description(edited.getDescription())
                .systemPrompt(edited.getSystemPrompt())
                .userPrompt(edited.getUserPrompt())
                .requiredVariables(edited.getRequiredVariables())
                .responseSchema(edited.getResponseSchema())
                .expectsJson(edited.getExpectsJson())
                .modelHint(edited.getModelHint())
                .temperature(edited.getTemperature())
                .maxOutputTokens(edited.getMaxOutputTokens())
                .preferFastModel(edited.getPreferFastModel())
                .usesRetrieval(edited.getUsesRetrieval())
                .retrievalSourceTypes(edited.getRetrievalSourceTypes())
                .retrievalTopK(edited.getRetrievalTopK())
                .previousVersionId(edited.getId())
                .changeNote(changeNote)
                .createdBy(userId)
                .tenantId(tenantId)
                .build();

        AiPromptTemplate saved = repository.save(fresh);
        log.info("[AI-PROMPT] published '{}' v{} | tenantId={} by userId={}",
                saved.getTemplateKey(), saved.getVersion(), tenantId, userId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AiPromptTemplate> history(String templateKey) {
        return repository.findByTemplateKeyOrderByVersionDesc(templateKey);
    }

    @Transactional(readOnly = true)
    public List<AiPromptTemplate> listActive() {
        return repository.findByActiveTrueOrderByTemplateKeyAsc();
    }
}
