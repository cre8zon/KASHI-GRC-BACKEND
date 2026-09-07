package com.kashi.grc.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.config.AiProperties.ProviderConfig;
import com.kashi.grc.ai.config.AiProperties.WireFormat;
import com.kashi.grc.ai.domain.AiEnums.ProviderType;
import com.kashi.grc.ai.provider.LlmDtos.LlmMessage;
import com.kashi.grc.ai.provider.LlmDtos.LlmRequest;
import com.kashi.grc.ai.provider.LlmDtos.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One provider class for every vendor, switched by wire format.
 *
 * ── WHY ONE CLASS AND NOT ONE PER VENDOR ─────────────────────────────────────
 * Grok, Gemini, Perplexity, DeepSeek, Mistral, Groq, Together, Azure OpenAI and
 * anything behind vLLM or LiteLLM all speak the same /chat/completions shape.
 * Writing a class per vendor would be the same two hundred lines copied eight
 * times, and every fix would need applying eight times.
 *
 * Anthropic is the one real exception and it differs in exactly three ways,
 * handled inline below rather than in a separate class:
 *
 *   1. system prompt is a TOP-LEVEL FIELD, not a message. Sent as a message it
 *      is accepted and silently ignored — you lose every constraint you wrote
 *      and the output still looks plausible, which is the worst failure mode
 *      available.
 *   2. max_tokens is REQUIRED. No server-side default to fall back on.
 *   3. No JSON mode. Handled by prefilling the assistant turn with "{" so the
 *      model continues from inside an object, which removes the "Here is the
 *      JSON:" preamble that breaks every parser.
 *
 * Instances are built by LlmProviderRegistry from the properties map, not by
 * component scanning, because the set of providers is configuration.
 */
@Slf4j
public class GenericLlmProvider implements LlmProvider {

    private final String key;
    private final ProviderConfig cfg;
    private final RestClient rest;
    private final ObjectMapper mapper;

    public GenericLlmProvider(String key, ProviderConfig cfg, ObjectMapper mapper) {
        this.key = key;
        this.cfg = cfg;
        this.mapper = mapper;

        RestClient.Builder b = RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);

        // Auth header differs by wire format. Anthropic uses x-api-key plus a
        // version header; everyone else uses a bearer token.
        if (cfg.getWireFormat() == WireFormat.ANTHROPIC) {
            b.defaultHeader("x-api-key", cfg.getApiKey());
            b.defaultHeader("anthropic-version", "2023-06-01");
        } else {
            b.defaultHeader("Authorization", "Bearer " + cfg.getApiKey());
        }
        cfg.getHeaders().forEach(b::defaultHeader);

        this.rest = b.build();
    }

    /**
     * Implements LlmProvider.key(). NOT a Lombok @Getter — the interface
     * declares key(), and @Getter would generate getKey(), which satisfies
     * nothing and leaves the class abstract.
     */
    @Override public String key() { return key; }

    @Override public ProviderType type() {
        return cfg.getWireFormat() == WireFormat.ANTHROPIC ? ProviderType.ANTHROPIC : ProviderType.OPENAI;
    }
    @Override public boolean isConfigured()    { return cfg.isConfigured(); }
    @Override public String  defaultChatModel(){ return cfg.getChatModel(); }
    @Override public String  defaultFastModel(){
        return cfg.getFastModel() != null && !cfg.getFastModel().isBlank()
                ? cfg.getFastModel() : cfg.getChatModel();
    }

    public ProviderConfig config() { return cfg; }
    public String displayName() {
        return cfg.getDisplayName() != null ? cfg.getDisplayName() : key;
    }

    // ── Blocking ──────────────────────────────────────────────────────────────

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!isConfigured()) throw AiProviderException.notConfigured(key);
        long started = System.currentTimeMillis();
        boolean anthropic = cfg.getWireFormat() == WireFormat.ANTHROPIC;

        try {
            String raw = rest.post()
                    .uri(anthropic ? "/messages" : "/chat/completions")
                    .body(buildBody(request, false))
                    .retrieve().body(String.class);

            JsonNode root = mapper.readTree(raw);
            return anthropic ? parseAnthropic(root, request, started)
                    : parseOpenAi(root, request, started);

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw translate(e);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw AiProviderException.unavailable(key, e.getMessage());
        }
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onToken) {
        if (!cfg.isSupportsStreaming()) throw new UnsupportedOperationException(key + " streaming disabled");
        if (!isConfigured()) throw AiProviderException.notConfigured(key);

        long started = System.currentTimeMillis();
        boolean anthropic = cfg.getWireFormat() == WireFormat.ANTHROPIC;

        StringBuilder assembled = new StringBuilder();
        final Integer[] tokens = new Integer[2];
        final String[]  finish = new String[1];
        final String[]  model  = { request.getModel() };

        try {
            rest.post()
                    .uri(anthropic ? "/messages" : "/chat/completions")
                    .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(buildBody(request, true))
                    .exchange((req, res) -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                String payload = line.substring(5).trim();
                                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                                JsonNode node = mapper.readTree(payload);
                                if (anthropic) {
                                    switch (node.path("type").asText()) {
                                        case "content_block_delta" -> {
                                            String d = node.path("delta").path("text").asText("");
                                            if (!d.isEmpty()) { assembled.append(d); onToken.accept(d); }
                                        }
                                        case "message_start" -> {
                                            JsonNode u = node.path("message").path("usage");
                                            if (u.hasNonNull("input_tokens")) tokens[0] = u.get("input_tokens").asInt();
                                        }
                                        case "message_delta" -> {
                                            JsonNode u = node.path("usage");
                                            if (u.hasNonNull("output_tokens")) tokens[1] = u.get("output_tokens").asInt();
                                            if (node.path("delta").hasNonNull("stop_reason"))
                                                finish[0] = node.path("delta").get("stop_reason").asText();
                                        }
                                        default -> { }
                                    }
                                } else {
                                    JsonNode choice = node.path("choices").path(0);
                                    String d = choice.path("delta").path("content").asText("");
                                    if (!d.isEmpty()) { assembled.append(d); onToken.accept(d); }
                                    if (choice.hasNonNull("finish_reason")) finish[0] = choice.get("finish_reason").asText();
                                    if (node.hasNonNull("model")) model[0] = node.get("model").asText();
                                    JsonNode usage = node.path("usage");
                                    if (!usage.isMissingNode() && usage.hasNonNull("prompt_tokens")) {
                                        tokens[0] = usage.get("prompt_tokens").asInt();
                                        tokens[1] = usage.path("completion_tokens").asInt();
                                    }
                                }
                            }
                        }
                        return null;
                    });
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw translate(e);
        } catch (Exception e) {
            throw AiProviderException.unavailable(key, e.getMessage());
        }

        String content = assembled.toString();
        if (anthropic && request.isJsonMode() && !content.trim().startsWith("{")) content = "{" + content;

        return new LlmResponse(content, model[0], type(), tokens[0], tokens[1],
                finish[0], System.currentTimeMillis() - started, 0);
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    private Map<String, Object> buildBody(LlmRequest r, boolean stream) {
        return cfg.getWireFormat() == WireFormat.ANTHROPIC
                ? anthropicBody(r, stream) : openAiBody(r, stream);
    }

    private Map<String, Object> openAiBody(LlmRequest r, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.getModel() != null ? r.getModel() : cfg.getChatModel());

        List<Map<String, String>> msgs = new ArrayList<>();
        for (LlmMessage m : r.getMessages()) {
            Map<String, String> one = new HashMap<>();
            one.put("role", switch (m.role()) {
                case SYSTEM -> "system"; case USER -> "user"; case ASSISTANT -> "assistant";
            });
            one.put("content", m.content());
            msgs.add(one);
        }
        body.put("messages", msgs);

        if (r.getTemperature() != null)     body.put("temperature", r.getTemperature());
        if (r.getMaxOutputTokens() != null) body.put("max_tokens", r.getMaxOutputTokens());
        if (r.getStopSequences() != null && !r.getStopSequences().isEmpty())
            body.put("stop", r.getStopSequences());
        if (r.isJsonMode() && cfg.isSupportsJsonMode())
            body.put("response_format", Map.of("type", "json_object"));
        if (stream) {
            body.put("stream", true);
            // Without include_usage a streamed call reports no token counts at
            // all, so it never lands in ai_usage_counters. A silently unmetered
            // call is worse than no budget guard, because the guard looks fine.
            body.put("stream_options", Map.of("include_usage", true));
        }
        return body;
    }

    private Map<String, Object> anthropicBody(LlmRequest r, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.getModel() != null ? r.getModel() : cfg.getChatModel());
        body.put("max_tokens", r.getMaxOutputTokens() != null
                ? r.getMaxOutputTokens() : cfg.getMaxOutputTokens());          // difference 2

        String system = r.systemPrompt();
        if (system != null && !system.isBlank()) body.put("system", system);   // difference 1

        List<Map<String, Object>> msgs = new ArrayList<>();
        for (LlmMessage m : r.nonSystemMessages()) {
            msgs.add(Map.of("role", m.role() == LlmDtos.Role.ASSISTANT ? "assistant" : "user",
                    "content", m.content()));
        }
        if (r.isJsonMode()) msgs.add(Map.of("role", "assistant", "content", "{"));  // difference 3
        body.put("messages", msgs);

        if (r.getTemperature() != null) body.put("temperature", r.getTemperature());
        if (r.getStopSequences() != null && !r.getStopSequences().isEmpty())
            body.put("stop_sequences", r.getStopSequences());
        if (stream) body.put("stream", true);
        return body;
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private LlmResponse parseOpenAi(JsonNode root, LlmRequest req, long started) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode usage  = root.path("usage");
        return new LlmResponse(
                choice.path("message").path("content").asText(""),
                root.path("model").asText(req.getModel()), type(),
                usage.hasNonNull("prompt_tokens")     ? usage.get("prompt_tokens").asInt()     : null,
                usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null,
                choice.path("finish_reason").asText(null),
                System.currentTimeMillis() - started, 0);
    }

    private LlmResponse parseAnthropic(JsonNode root, LlmRequest req, long started) {
        JsonNode usage = root.path("usage");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
        }
        String content = text.toString();
        if (req.isJsonMode() && !content.trim().startsWith("{")) content = "{" + content;

        return new LlmResponse(content, root.path("model").asText(req.getModel()), type(),
                usage.hasNonNull("input_tokens")  ? usage.get("input_tokens").asInt()  : null,
                usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null,
                root.path("stop_reason").asText(null),
                System.currentTimeMillis() - started, 0);
    }

    // ── Errors ────────────────────────────────────────────────────────────────

    /**
     * The retryable/not distinction is the one that matters. Retrying a 400
     * three times with backoff turns a fast, clear failure into a slow identical
     * one while the user watches a spinner.
     */
    private AiProviderException translate(org.springframework.web.client.HttpStatusCodeException e) {
        int code = e.getStatusCode().value();
        String detail = e.getResponseBodyAsString();
        if (detail != null && detail.length() > 500) detail = detail.substring(0, 500) + "...";
        log.warn("[AI-{}] HTTP {} | {}", key.toUpperCase(), code, detail);
        return switch (code) {
            case 429 -> AiProviderException.rateLimited(key);
            case 401, 403 -> new AiProviderException("AI_PROVIDER_AUTH",
                    displayName() + " rejected the credentials",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, false);
            case 400, 404, 422 -> AiProviderException.badRequest(key, detail);
            case 529 -> AiProviderException.unavailable(key, "overloaded");
            default -> AiProviderException.unavailable(key, "HTTP " + code + " " + detail);
        };
    }
}