package com.kashi.grc.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every AI setting, bound from `app.ai.*` in application.properties.
 *
 * ── PROVIDERS ARE A MAP, NOT FIXED FIELDS ────────────────────────────────────
 * v1 had `openai` and `anthropic` as hardcoded nested objects, which meant
 * adding Grok was a code change. It is now an open map keyed by whatever name
 * you choose, so a new vendor is four lines of properties and a restart:
 *
 *   app.ai.providers.grok.wire-format=openai
 *   app.ai.providers.grok.base-url=https://api.x.ai/v1
 *   app.ai.providers.grok.api-key=xai-...
 *   app.ai.providers.grok.chat-model=grok-4
 *
 * ── WHY ONLY TWO WIRE FORMATS COVER EVERYTHING ───────────────────────────────
 * Almost the entire market speaks OpenAI's /chat/completions shape: xAI (Grok),
 * Google Gemini via its OpenAI-compatible endpoint, Perplexity, DeepSeek,
 * Mistral, Groq, Together, Azure OpenAI, and anything self-hosted behind vLLM,
 * Ollama or LiteLLM. Anthropic is the one meaningful exception — system prompt
 * as a top-level field, mandatory max_tokens, no JSON mode.
 *
 * So `wire-format` takes `openai` or `anthropic` and that is genuinely the whole
 * space. A third format would be a new enum value and one method, not a
 * redesign.
 *
 * ── EMBEDDINGS ARE CONFIGURED SEPARATELY, ON PURPOSE ─────────────────────────
 * Chat and embeddings are different decisions. Grok and Perplexity do not offer
 * an embeddings endpoint at all, so "use Grok for everything" is not a
 * configuration that exists. You will typically run chat on one vendor and
 * embeddings on OpenAI or Gemini, and the embedding model is pinned far harder
 * because changing it invalidates the whole index.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** Master switch. False = every AI endpoint returns 503 and nothing calls out. */
    private boolean enabled = false;

    /**
     * Which key in the `providers` map to use when the tenant has not pinned one.
     * Must match a configured provider name or startup logs a warning and falls
     * back to the first configured one.
     */
    private String defaultProvider = "grok";

    /** Named providers. Key is yours to choose: grok, gemini, openai, claude... */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    private final Embedding embedding = new Embedding();
    private final Qdrant    qdrant    = new Qdrant();
    private final Budget    budget    = new Budget();
    private final Guardrail guardrail = new Guardrail();
    private final Retrieval retrieval = new Retrieval();

    public enum WireFormat { OPENAI, ANTHROPIC }

    @Getter @Setter
    public static class ProviderConfig {
        /** openai | anthropic. Determines request shape, not the vendor. */
        private WireFormat wireFormat = WireFormat.OPENAI;

        /** Full base URL including any version segment, e.g. https://api.x.ai/v1 */
        private String  baseUrl;
        private String  apiKey;

        /** Shown in the admin UI. Cosmetic. */
        private String  displayName;

        private String  chatModel;
        /** Cheaper model for mechanical steps: critique, extraction, classification. */
        private String  fastModel;

        private int     connectTimeoutSeconds = 10;
        private int     readTimeoutSeconds    = 120;
        private int     maxRetries            = 2;
        private double  temperature           = 0.2;
        private int     maxOutputTokens       = 4096;

        /** Some gateways reject response_format. Turn off and rely on prompt + repair. */
        private boolean supportsJsonMode      = true;
        private boolean supportsStreaming     = true;

        /** Cost per million tokens in micro-units, for reporting only. */
        private long    inputCostPerMillionMicros  = 0L;
        private long    outputCostPerMillionMicros = 0L;

        /** Extra headers, e.g. Azure api-key or an OpenRouter referer. */
        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && chatModel != null && !chatModel.isBlank();
        }
    }

    @Getter @Setter
    public static class Embedding {
        /** Own credentials — the chat provider often has no embeddings endpoint. */
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model   = "text-embedding-3-small";
        /**
         * Matryoshka truncation: 512 keeps most retrieval quality at a third of
         * the storage. CHANGING THIS INVALIDATES EVERY STORED VECTOR — the
         * dimension is written onto each chunk row so a change is detectable and
         * only affected rows are re-embedded.
         */
        private int    dimensions = 512;
        private int    batchSize  = 64;
        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    @Getter @Setter
    public static class Qdrant {
        private String  url        = "http://localhost:6333";
        private String  apiKey;
        private String  collection = "kashi_grc_chunks";
        private int     connectTimeoutSeconds = 5;
        private int     readTimeoutSeconds    = 30;
        private boolean autoCreateCollection  = true;
    }

    @Getter @Setter
    public static class Budget {
        private long   monthlyTokensPerTenant = 5_000_000L;
        private long   tokensPerPipelineRun   = 200_000L;
        private int    maxPipelineSteps       = 12;
        private double warnThreshold          = 0.8;
    }

    @Getter @Setter
    public static class Guardrail {
        private boolean redactPii             = true;
        private boolean scanPromptInjection   = true;
        private int     maxPromptChars        = 400_000;
        private int     maxJsonRepairAttempts = 1;
    }

    @Getter @Setter
    public static class Retrieval {
        private int    topK              = 8;
        private double minScore          = 0.30;
        private int    maxContextChars   = 24_000;
        private int    chunkSizeChars    = 1_800;
        private int    chunkOverlapChars = 250;
    }
}