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
 * ── FAILOVER RESPECTS THE PIN ────────────────────────────────────────────────
 * If a tenant pinned a provider, silently falling back to a different vendor on
 * error would violate the exact commitment the pin represents. So failover
 * (completeWithFailover / streamWithFailover) walks a chain built by chain():
 *   1. the chosen provider and model
 *   2. that provider's fallback-models, then its other configured model —
 *      same vendor, so allowed even for a pinned tenant
 *   3. other configured providers — ONLY when nothing pinned the provider
 * A call that pinned the model itself (an eval run) gets no failover at all.
 *
 * Every candidate but the last gets a single attempt: Gemini can take ~10 s to
 * answer "high demand", and three of those before trying the next model is
 * 30 s of spinner. The last candidate gets the provider's full retry budget.
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
        return completeWithRetry(provider, request, null);
    }

    /** @param maxRetriesOverride null = the provider's configured budget */
    public LlmResponse completeWithRetry(LlmProvider provider, LlmRequest request, Integer maxRetriesOverride) {
        int max = maxRetriesOverride != null ? maxRetriesOverride
                : provider instanceof GenericLlmProvider g ? g.config().getMaxRetries() : 2;
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

    // ── Failover ──────────────────────────────────────────────────────────────

    /** One provider + model to try. */
    public record Candidate(LlmProvider provider, String model) {}

    /** What actually answered, so the caller records the right vendor and model. */
    public record Served(LlmProvider provider, String model, LlmResponse response) {}

    /**
     * The ordered list of provider/model pairs to try for one call.
     *
     * @param providerPinned the caller or the tenant chose this vendor — never leave it
     * @param modelPinned    the caller fixed the exact model — try nothing else
     * @param preferFast     the template asks for the fast model on other vendors too
     */
    public List<Candidate> chain(LlmProvider primary, String model,
                                 boolean providerPinned, boolean modelPinned, boolean preferFast) {
        List<Candidate> out = new ArrayList<>();
        out.add(new Candidate(primary, model));
        if (modelPinned) return out;

        // Same vendor: configured fallbacks, then its other model.
        if (primary instanceof GenericLlmProvider g) {
            for (String m : g.config().getFallbackModels()) addIfNew(out, primary, m);
            addIfNew(out, primary, preferFast ? g.defaultChatModel() : g.defaultFastModel());
        }

        // Other vendors, only for tenants that expressed no preference.
        if (!providerPinned) {
            List<GenericLlmProvider> others = new ArrayList<>();
            GenericLlmProvider def = byKey.get(props.getDefaultProvider().toLowerCase());
            if (def != null) others.add(def);
            byKey.values().forEach(p -> { if (!others.contains(p)) others.add(p); });
            for (GenericLlmProvider p : others) {
                if (!p.isConfigured() || p.key().equals(primary.key())) continue;
                addIfNew(out, p, preferFast ? p.defaultFastModel() : p.defaultChatModel());
            }
        }
        return out;
    }

    private static void addIfNew(List<Candidate> out, LlmProvider p, String model) {
        if (model == null || model.isBlank()) return;
        boolean seen = out.stream().anyMatch(c -> c.provider().key().equals(p.key()) && model.equals(c.model()));
        if (!seen) out.add(new Candidate(p, model.trim()));
    }

    /** Blocking completion down the chain. Bad requests stop it: every vendor would refuse them. */
    public Served completeWithFailover(List<Candidate> chain, LlmRequest request) {
        AiProviderException last = null;
        for (int i = 0; i < chain.size(); i++) {
            Candidate c = chain.get(i);
            boolean lastCandidate = i == chain.size() - 1;
            request.model(c.model());
            try {
                LlmResponse r = completeWithRetry(c.provider(), request, lastCandidate ? null : 0);
                if (i > 0) log.warn("[AI-REGISTRY] served by fallback {}/{} after {} failed", c.provider().key(),
                        c.model(), chain.get(0).provider().key() + "/" + chain.get(0).model());
                return new Served(c.provider(), c.model(), r);
            } catch (AiProviderException e) {
                last = e;
                if (lastCandidate || !worthFailingOver(e)) throw e;
                log.warn("[AI-REGISTRY] {}/{} failed ({}: {}) — trying {}/{}", c.provider().key(), c.model(),
                        e.getErrorCode(), e.getDetail(), chain.get(i + 1).provider().key(), chain.get(i + 1).model());
            }
        }
        throw last != null ? last : AiProviderException.unavailable("ai", "empty provider chain");
    }

    /**
     * Streaming down the chain — but only until the first token reaches the
     * browser. After that, switching model would splice two answers together,
     * so a mid-stream failure is final.
     */
    public Served streamWithFailover(List<Candidate> chain, LlmRequest request, Consumer<String> onToken) {
        java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean(false);
        Consumer<String> tracking = t -> { emitted.set(true); onToken.accept(t); };
        AiProviderException last = null;
        for (int i = 0; i < chain.size(); i++) {
            Candidate c = chain.get(i);
            boolean lastCandidate = i == chain.size() - 1;
            request.model(c.model());
            try {
                return new Served(c.provider(), c.model(), streamOrFallback(c.provider(), request, tracking));
            } catch (AiProviderException e) {
                last = e;
                if (lastCandidate || emitted.get() || !worthFailingOver(e)) throw e;
                log.warn("[AI-REGISTRY] {}/{} stream failed ({}: {}) — trying {}/{}", c.provider().key(), c.model(),
                        e.getErrorCode(), e.getDetail(), chain.get(i + 1).provider().key(), chain.get(i + 1).model());
            }
        }
        throw last != null ? last : AiProviderException.unavailable("ai", "empty provider chain");
    }

    /** Busy, rate-limited, down, or refusing our key: another model may well answer. */
    private static boolean worthFailingOver(AiProviderException e) {
        return e.isRetryable() || "AI_PROVIDER_AUTH".equals(e.getErrorCode())
                || "AI_PROVIDER_NOT_CONFIGURED".equals(e.getErrorCode());
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