package com.kashi.grc.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI embeddings on RestClient.
 *
 * ── THE `dimensions` PARAMETER IS THE POINT ──────────────────────────────────
 * text-embedding-3-* are Matryoshka models: the leading N components are
 * independently meaningful, so asking for 512 instead of 1536 gives a vector
 * that is a third of the size and loses very little retrieval quality. Across a
 * corpus that will eventually hold every policy, control and vendor report for
 * every tenant, that is a third of the Qdrant memory and a third of the distance
 * computation, for a difference you will struggle to measure.
 *
 * ── ORDER IS A CONTRACT ──────────────────────────────────────────────────────
 * The API returns objects carrying an `index`; they are sorted by it here rather
 * than trusted to arrive in order. IngestionService pairs vectors to chunks
 * positionally, so a reordering would silently attach every policy's vector to
 * its neighbour — a corruption that no unit test notices and that surfaces
 * months later as "retrieval is just bad".
 */
@Slf4j
@Component
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final AiProperties.Embedding embedCfg;
    private final RestClient rest;
    private final ObjectMapper mapper;

    public OpenAiEmbeddingProvider(AiProperties props, ObjectMapper mapper) {
        this.embedCfg = props.getEmbedding();
        this.mapper   = mapper;
        /*
         * Embeddings have their own base URL and key, separate from chat.
         * Grok and Perplexity have no embeddings endpoint at all, so "run
         * everything on one vendor" is not a configuration that exists — you
         * will run chat on one and embeddings on OpenAI or Gemini.
         */
        RestClient.Builder b = RestClient.builder()
                .baseUrl(embedCfg.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", "Bearer " + embedCfg.getApiKey());
        embedCfg.getHeaders().forEach(b::defaultHeader);
        this.rest = b.build();
    }

    @Override public String  key()          { return "embedding"; }
    @Override public boolean isConfigured() { return embedCfg.isConfigured(); }
    @Override public String  model()        { return embedCfg.getModel(); }
    @Override public int     dimensions()   { return embedCfg.getDimensions(); }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (!isConfigured()) throw AiProviderException.notConfigured(key());

        List<float[]> out = new ArrayList<>(texts.size());
        int batch = Math.max(1, embedCfg.getBatchSize());

        // Chunked because most providers reject inputs beyond ~96 texts per call.
        for (int i = 0; i < texts.size(); i += batch) {
            out.addAll(embedBatch(texts.subList(i, Math.min(texts.size(), i + batch))));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embedCfg.getModel());
        body.put("input", batch);
        // Only send when the model supports it; ada-002 rejects the parameter outright.
        if (embedCfg.getModel() != null && embedCfg.getModel().startsWith("text-embedding-3")) {
            body.put("dimensions", embedCfg.getDimensions());
        }

        try {
            String raw = rest.post()
                    .uri("/embeddings")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode data = mapper.readTree(raw).path("data");

            // Materialise then sort by `index` — never trust arrival order.
            List<JsonNode> nodes = new ArrayList<>();
            data.forEach(nodes::add);
            nodes.sort((a, b) -> Integer.compare(a.path("index").asInt(), b.path("index").asInt()));

            List<float[]> vectors = new ArrayList<>(nodes.size());
            for (JsonNode n : nodes) {
                JsonNode arr = n.path("embedding");
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) v[i] = (float) arr.get(i).asDouble();
                vectors.add(v);
            }

            if (vectors.size() != batch.size()) {
                throw new IllegalStateException(
                        "Embedding count mismatch: sent " + batch.size() + ", received " + vectors.size());
            }
            return vectors;

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            log.warn("[AI-EMBED] HTTP {} | {}", code, e.getResponseBodyAsString());
            if (code == 429) throw AiProviderException.rateLimited(key());
            throw AiProviderException.unavailable(key(), "embeddings HTTP " + code);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw AiProviderException.unavailable(key(), e.getMessage());
        }
    }
}