package com.kashi.grc.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.provider.LlmDtos.LlmRequest;
import com.kashi.grc.ai.provider.LlmDtos.LlmResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Builds providers from configuration, chooses one per call, owns retry.
 *
 * ── PROVIDERS ARE BUILT, NOT SCANNED ─────────────────────────────────────────
 * There is no @Component per vendor. The registry reads app.ai.providers.* at
 * startup and instantiates a GenericLlmProvider for each configured entry, so
 * adding Grok or swapping Gemini for Perplexity is a properties edit and a
 * restart. Nothing above this package knows which vendor served a call.
 *
 * ── RESOLUTION ORDER ─────────────────────────────────────────────────────────
 *   1. explicit key from the caller (an eval run pinning a vendor)
 *   2. the tenant's ai_org_profiles.preferred_provider
 *   3. app.ai.default-provider
 *   4. any configured provider
 *
 * Step 2 exists because enterprise buyers ask which sub-processors handle their
 * data and whether they can choose. Being able to answer yes closes that thread
 * in one line.
 *
 * ── FAILOVER IS DELIBERATELY NOT AUTOMATIC ───────────────────────────────────
 * If a tenant pinned a provider, silently falling back to a different vendor on
 * error would violate the exact commitment the pin represents. Failover only
 * happens for tenants that expressed no preference.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmProviderRegistry {

    private final AiProperties props;
    private final ObjectMapper mapper;

    private final Map<String, GenericLlmProvider> byKey = new LinkedHashMap<>();

    @PostConstruct
    void build() {
        props.getProviders().forEach((name, cfg) -> {
            String key = name.toLowerCase();
            GenericLlmProvider p = new GenericLlmProvider(key, cfg, mapper);
            byKey.put(key, p);
            if (!cfg.isConfigured()) {
                log.warn("[AI-REGISTRY] provider '{}' declared but incomplete — needs base-url, api-key and chat-model", key);
            }
        });

        List<String> ready = configuredKeys();
        if (ready.isEmpty()) {
            log.warn("[AI-REGISTRY] NO PROVIDER CONFIGURED. Set app.ai.providers.<name>.api-key in application.properties.");
        } else {
            log.info("[AI-REGISTRY] declared={} configured={} default={}",
                    byKey.keySet(), ready, props.getDefaultProvider());
            if (!ready.contains(props.getDefaultProvider().toLowerCase())) {
                log.warn("[AI-REGISTRY] app.ai.default-provider='{}' is not configured — falling back to '{}'",
                        props.getDefaultProvider(), ready.get(0));
            }
        }
    }

    public LlmProvider resolve(String explicitKey, String tenantPreference) {
        LlmProvider p = pick(explicitKey);
        if (p != null) return p;
        p = pick(tenantPreference);
        if (p != null) return p;
        p = pick(props.getDefaultProvider());
        if (p != null) return p;

        return byKey.values().stream().filter(LlmProvider::isConfigured).findFirst()
                .orElseThrow(() -> new AiProviderException("AI_NO_PROVIDER",
                        "No AI provider is configured. Set app.ai.providers.<name>.api-key in application.properties.",
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, false));
    }

    private LlmProvider pick(String key) {
        if (key == null || key.isBlank()) return null;
        GenericLlmProvider p = byKey.get(key.toLowerCase());
        return (p != null && p.isConfigured()) ? p : null;
    }

    /**
     * Bounded retry with jittered exponential backoff.
     *
     * Jitter matters more than it looks: without it a burst of parallel
     * generations that all hit a 429 retry in lockstep and hit the limit again
     * together. Random spread turns a thundering herd back into traffic.
     */
    public LlmResponse completeWithRetry(LlmProvider provider, LlmRequest request) {
        int max = provider instanceof GenericLlmProvider g ? g.config().getMaxRetries() : 2;
        AiProviderException last = null;

        for (int attempt = 0; attempt <= max; attempt++) {
            try {
                LlmResponse r = provider.complete(request);
                return attempt == 0 ? r : new LlmResponse(r.content(), r.model(), r.provider(),
                        r.promptTokens(), r.completionTokens(), r.finishReason(), r.latencyMs(), attempt);
            } catch (AiProviderException e) {
                last = e;
                if (!e.isRetryable() || attempt == max) break;
                long backoff = (long) (Math.pow(2, attempt) * 500) + ThreadLocalRandom.current().nextInt(250);
                log.warn("[AI-REGISTRY] {} attempt {}/{} failed ({}), backing off {}ms",
                        provider.key(), attempt + 1, max + 1, e.getErrorCode(), backoff);
                try { Thread.sleep(backoff); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw last != null ? last : AiProviderException.unavailable(provider.key(), "exhausted retries");
    }

    /**
     * Streaming has NO retry. Tokens are already in the browser; restarting
     * mid-stream would render the first half twice.
     */
    public LlmResponse streamOrFallback(LlmProvider provider, LlmRequest request, Consumer<String> onToken) {
        try {
            return provider.stream(request, onToken);
        } catch (UnsupportedOperationException e) {
            log.debug("[AI-REGISTRY] {} has no streaming, falling back to blocking", provider.key());
            LlmResponse r = completeWithRetry(provider, request);
            onToken.accept(r.content());
            return r;
        }
    }

    public List<String> configuredKeys() {
        return byKey.values().stream().filter(LlmProvider::isConfigured)
                .map(LlmProvider::key).toList();
    }

    /** For the admin screen: what is available and which is active. */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GenericLlmProvider p : byKey.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key",         p.key());
            m.put("displayName", p.displayName());
            m.put("wireFormat",  p.config().getWireFormat().name());
            m.put("chatModel",   p.config().getChatModel());
            m.put("fastModel",   p.defaultFastModel());
            m.put("configured",  p.isConfigured());
            m.put("isDefault",   p.key().equalsIgnoreCase(props.getDefaultProvider()));
            out.add(m);   // API key deliberately never included
        }
        return out;
    }

    public AiProperties.ProviderConfig configFor(LlmProvider p) {
        return p instanceof GenericLlmProvider g ? g.config() : null;
    }
}