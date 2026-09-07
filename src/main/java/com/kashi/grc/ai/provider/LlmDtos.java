package com.kashi.grc.ai.provider;

import com.kashi.grc.ai.domain.AiEnums.ProviderType;

import java.util.ArrayList;
import java.util.List;

/**
 * The provider-neutral request/response shapes. Grouped in one file following
 * the CommonControlDtos convention.
 *
 * ── WHY A NEUTRAL SHAPE AT ALL ───────────────────────────────────────────────
 * OpenAI and Anthropic differ in ways that leak everywhere if you let them: the
 * system prompt is a message for one and a top-level field for the other; token
 * counts sit under different keys; one calls the cap max_tokens and the other
 * max_completion_tokens. Translating once, at the provider boundary, keeps every
 * layer above this file ignorant of which vendor is serving the call — which is
 * exactly what makes "switch this tenant to Anthropic" a config change.
 */
public final class LlmDtos {

    private LlmDtos() {}

    public enum Role { SYSTEM, USER, ASSISTANT }

    public record LlmMessage(Role role, String content) {
        public static LlmMessage system(String c)    { return new LlmMessage(Role.SYSTEM, c); }
        public static LlmMessage user(String c)      { return new LlmMessage(Role.USER, c); }
        public static LlmMessage assistant(String c) { return new LlmMessage(Role.ASSISTANT, c); }
    }

    /**
     * One call's worth of intent. Built by AiChatService, never by a caller —
     * that is what guarantees no model call bypasses the guardrails and the
     * interaction log.
     */
    public static class LlmRequest {
        private final List<LlmMessage> messages = new ArrayList<>();
        private String  model;
        private Double  temperature;
        private Integer maxOutputTokens;
        /** Ask the provider for a JSON object natively where it supports one. */
        private boolean jsonMode;
        /** Sequences that end generation early — used by the section-rewrite task. */
        private List<String> stopSequences;

        public LlmRequest addMessage(LlmMessage m) { this.messages.add(m); return this; }
        public LlmRequest model(String m)          { this.model = m; return this; }
        public LlmRequest temperature(Double t)    { this.temperature = t; return this; }
        public LlmRequest maxOutputTokens(Integer m) { this.maxOutputTokens = m; return this; }
        public LlmRequest jsonMode(boolean j)      { this.jsonMode = j; return this; }
        public LlmRequest stopSequences(List<String> s) { this.stopSequences = s; return this; }

        public List<LlmMessage> getMessages()  { return messages; }
        public String  getModel()              { return model; }
        public Double  getTemperature()        { return temperature; }
        public Integer getMaxOutputTokens()    { return maxOutputTokens; }
        public boolean isJsonMode()            { return jsonMode; }
        public List<String> getStopSequences() { return stopSequences; }

        /** Rough character total, used by the oversize guardrail before any network call. */
        public int approximateChars() {
            int n = 0;
            for (LlmMessage m : messages) n += m.content() == null ? 0 : m.content().length();
            return n;
        }

        public String systemPrompt() {
            return messages.stream()
                    .filter(m -> m.role() == Role.SYSTEM)
                    .map(LlmMessage::content)
                    .findFirst().orElse(null);
        }

        public List<LlmMessage> nonSystemMessages() {
            return messages.stream().filter(m -> m.role() != Role.SYSTEM).toList();
        }
    }

    /**
     * What came back, normalised.
     *
     * promptTokens/completionTokens are taken from the provider's own usage block
     * rather than estimated locally. Estimation is off by enough to make cost
     * reporting a fiction, and the provider is the one sending the invoice.
     */
    public record LlmResponse(
            String       content,
            String       model,
            ProviderType provider,
            Integer      promptTokens,
            Integer      completionTokens,
            String       finishReason,
            long         latencyMs,
            int          retryCount
    ) {
        public int totalTokens() {
            return (promptTokens == null ? 0 : promptTokens)
                 + (completionTokens == null ? 0 : completionTokens);
        }

        /**
         * True when the model hit its output cap. Worth surfacing rather than
         * swallowing: a truncated policy section looks like a finished one until
         * somebody reads to the end of it.
         */
        public boolean wasTruncated() {
            return "length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason);
        }
    }
}
