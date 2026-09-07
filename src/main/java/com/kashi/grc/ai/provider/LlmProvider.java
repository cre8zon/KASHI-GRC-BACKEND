package com.kashi.grc.ai.provider;

import com.kashi.grc.ai.domain.AiEnums.ProviderType;
import com.kashi.grc.ai.provider.LlmDtos.LlmRequest;
import com.kashi.grc.ai.provider.LlmDtos.LlmResponse;

import java.util.function.Consumer;

/**
 * A chat-completion vendor.
 *
 * ── WHY HAND-ROLLED RATHER THAN A FRAMEWORK ABSTRACTION ──────────────────────
 * An SDK or an AI framework would give roughly this interface and cost a
 * dependency tree you cannot audit, a version-compatibility question at every
 * Spring Boot upgrade, and a layer of behaviour between your prompt and the wire
 * that you did not write. Against that: two providers are about two hundred
 * lines each on RestClient, which is already on your classpath via
 * spring-boot-starter-web.
 *
 * The result is that this entire AI module adds ZERO new Maven dependencies.
 * For a GRC vendor whose customers audit its supply chain, that is not a
 * tidiness argument — it is a shorter security questionnaire.
 *
 * Adding a provider means implementing this interface and registering the bean.
 * Nothing above the provider package changes.
 */
public interface LlmProvider {

    ProviderType type();

    /** Key used in configuration and in ai_org_profiles.preferred_provider. */
    String key();

    /** False when no API key is configured — the registry then skips this provider. */
    boolean isConfigured();

    /** Model used when nothing upstream pinned one. */
    String defaultChatModel();

    /** Cheaper model for mechanical steps: critique, extraction, classification. */
    String defaultFastModel();

    /**
     * Blocking completion. Implementations MUST NOT retry internally — retry
     * policy belongs to LlmProviderRegistry so that backoff, budget accounting
     * and the interaction log stay consistent across vendors.
     */
    LlmResponse complete(LlmRequest request);

    /**
     * Streaming completion. `onToken` fires per delta; the returned response
     * carries the assembled content and final usage.
     *
     * Default throws: a provider may legitimately not support streaming, and
     * AiChatService falls back to complete() rather than failing the request.
     */
    default LlmResponse stream(LlmRequest request, Consumer<String> onToken) {
        throw new UnsupportedOperationException(key() + " does not support streaming");
    }
}
