package com.kashi.grc.ai.rag;

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
 * Qdrant over its REST API. No client library, no new dependency.
 *
 * ── COLLECTION LAYOUT ────────────────────────────────────────────────────────
 * ONE collection for every tenant, isolated by a payload filter, rather than a
 * collection per tenant. The reasoning:
 *
 *   - a collection per tenant means thousands of collections, each with its own
 *     HNSW graph and memory floor, and Qdrant is not designed for that shape
 *   - global platform corpus must be searchable ALONGSIDE tenant corpus in one
 *     query. Split collections would need two searches and a merge, and merging
 *     two similarity rankings correctly is harder than it looks
 *   - the filter is indexed, so it costs approximately nothing
 *
 * The trade is that isolation now depends on a filter being applied correctly,
 * so it is applied in exactly one place — buildFilter() below — and every search
 * path goes through it. That is a deliberate concentration of risk into code
 * that is small enough to read in one sitting.
 *
 * ── WHY tenantScope IS A STRING TOKEN ────────────────────────────────────────
 * Qdrant matches keywords. "global" or "t:42" is one indexed keyword match and
 * an `any` filter covers both in a single clause. Encoding it as a nullable
 * integer would need null-handling semantics that differ from SQL's, which is
 * precisely the sort of subtle mismatch that produces a leak.
 *
 * ── DEGRADED MODE ────────────────────────────────────────────────────────────
 * Qdrant being down must not take policy generation down with it. Search returns
 * empty and logs; the caller proceeds with structured context alone and produces
 * a slightly less grounded draft. Compare the alternative — a 500 on a button
 * the customer just clicked — and the choice is obvious. Mirrors the philosophy
 * already in RedisCircuitBreaker.
 */
@Slf4j
@Component
public class QdrantVectorStore implements VectorStore {

    private final AiProperties.Qdrant cfg;
    private final RestClient rest;
    private final ObjectMapper mapper;
    private volatile boolean collectionReady = false;

    public QdrantVectorStore(AiProperties props, ObjectMapper mapper) {
        this.cfg    = props.getQdrant();
        this.mapper = mapper;
        RestClient.Builder b = RestClient.builder()
                .baseUrl(cfg.getUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) b.defaultHeader("api-key", cfg.getApiKey());
        this.rest = b.build();
    }

    // ── Collection lifecycle ──────────────────────────────────────────────────

    @Override
    public void ensureCollection(int dimensions) {
        if (collectionReady) return;
        try {
            try {
                rest.get().uri("/collections/{c}", cfg.getCollection()).retrieve().body(String.class);
                collectionReady = true;
                log.info("[AI-QDRANT] collection '{}' present", cfg.getCollection());
                ensurePayloadIndexes();
                return;
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
                if (!cfg.isAutoCreateCollection()) {
                    log.error("[AI-QDRANT] collection '{}' missing and auto-create is off", cfg.getCollection());
                    return;
                }
            }

            rest.put()
                .uri("/collections/{c}", cfg.getCollection())
                .body(Map.of(
                        "vectors", Map.of("size", dimensions, "distance", "Cosine"),
                        // Deferred indexing: bulk ingestion writes far faster when the
                        // HNSW graph is built once at the end rather than per point.
                        "optimizers_config", Map.of("indexing_threshold", 20000)))
                .retrieve().body(String.class);

            log.info("[AI-QDRANT] created collection '{}' dim={} distance=Cosine", cfg.getCollection(), dimensions);
            ensurePayloadIndexes();
            collectionReady = true;

        } catch (Exception e) {
            log.error("[AI-QDRANT] ensureCollection failed — retrieval will run degraded: {}", e.getMessage());
        }
    }

    /**
     * Index the filter fields. Without these, the tenant filter degrades to a
     * full payload scan — which still returns CORRECT results, so nothing looks
     * broken, while search latency grows linearly with the corpus. The kind of
     * problem that only shows up once you have customers.
     */
    private void ensurePayloadIndexes() {
        for (String field : List.of("scope", "sourceType", "retrievable")) {
            try {
                rest.put()
                    .uri("/collections/{c}/index?wait=true", cfg.getCollection())
                    .body(Map.of("field_name", field, "field_schema", "retrievable".equals(field) ? "bool" : "keyword"))
                    .retrieve().body(String.class);
            } catch (Exception e) {
                log.debug("[AI-QDRANT] payload index '{}' not created (may already exist): {}", field, e.getMessage());
            }
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public void upsert(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) return;
        try {
            List<Map<String, Object>> body = new ArrayList<>(points.size());
            for (VectorPoint p : points) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("scope",       p.scope());
                payload.put("sourceType",  p.sourceType());
                payload.put("sourceId",    p.sourceId());
                payload.put("chunkId",     p.chunkId());
                payload.put("retrievable", true);
                if (p.payload() != null) payload.putAll(p.payload());

                body.add(Map.of("id", p.id(), "vector", toList(p.vector()), "payload", payload));
            }

            rest.put()
                .uri("/collections/{c}/points?wait=true", cfg.getCollection())
                .body(Map.of("points", body))
                .retrieve().body(String.class);

            log.debug("[AI-QDRANT] upserted {} point(s)", points.size());

        } catch (Exception e) {
            log.error("[AI-QDRANT] upsert of {} point(s) failed: {}", points.size(), e.getMessage());
            throw new com.kashi.grc.common.exception.BusinessException(
                    "AI_VECTOR_UPSERT_FAILED", "Could not write to the vector index",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    public List<ScoredPoint> search(float[] queryVector, List<String> scopes,
                                    List<String> sourceTypes, int topK, double minScore) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", toList(queryVector));
            body.put("limit", topK);
            body.put("with_payload", true);
            body.put("score_threshold", minScore);
            body.put("filter", buildFilter(scopes, sourceTypes));

            String raw = rest.post()
                    .uri("/collections/{c}/points/search", cfg.getCollection())
                    .body(body)
                    .retrieve().body(String.class);

            JsonNode result = mapper.readTree(raw).path("result");
            List<ScoredPoint> hits = new ArrayList<>();
            for (JsonNode n : result) {
                JsonNode payload = n.path("payload");
                Map<String, Object> meta = mapper.convertValue(payload, Map.class);
                hits.add(new ScoredPoint(
                        n.path("id").asText(),
                        n.path("score").asDouble(),
                        payload.hasNonNull("chunkId") ? payload.get("chunkId").asLong() : null,
                        payload.path("sourceType").asText(null),
                        payload.hasNonNull("sourceId") ? payload.get("sourceId").asLong() : null,
                        meta));
            }
            return hits;

        } catch (Exception e) {
            // Degrade, do not fail. See the class comment.
            log.error("[AI-QDRANT] search failed, returning no context: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * THE ISOLATION BOUNDARY. Every read path in the module funnels through here.
     *
     * `must` clauses are conjunctive, so scope is always enforced regardless of
     * what else is added. `any` inside it lets one query cover the platform
     * corpus and the caller's own corpus without a second round-trip. The
     * quarantine and retrievable flags ride along so a flagged document cannot
     * come back through a path that forgot about it.
     */
    private Map<String, Object> buildFilter(List<String> scopes, List<String> sourceTypes) {
        List<Map<String, Object>> must = new ArrayList<>();

        must.add(Map.of("key", "scope", "match", Map.of("any", scopes)));
        must.add(Map.of("key", "retrievable", "match", Map.of("value", true)));

        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            must.add(Map.of("key", "sourceType", "match", Map.of("any", sourceTypes)));
        }
        return Map.of("must", must);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public void deleteByIds(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) return;
        try {
            rest.post()
                .uri("/collections/{c}/points/delete?wait=true", cfg.getCollection())
                .body(Map.of("points", vectorIds))
                .retrieve().body(String.class);
        } catch (Exception e) {
            log.error("[AI-QDRANT] delete by ids failed: {}", e.getMessage());
        }
    }

    @Override
    public void deleteBySource(String sourceType, Long sourceId) {
        try {
            rest.post()
                .uri("/collections/{c}/points/delete?wait=true", cfg.getCollection())
                .body(Map.of("filter", Map.of("must", List.of(
                        Map.of("key", "sourceType", "match", Map.of("value", sourceType)),
                        Map.of("key", "sourceId",   "match", Map.of("value", sourceId))))))
                .retrieve().body(String.class);
        } catch (Exception e) {
            log.error("[AI-QDRANT] delete by source failed: {}", e.getMessage());
        }
    }

    /**
     * Erase a whole tenant. Required for offboarding and for a DPDP/GDPR erasure
     * request — and a genuine reason to keep the index rebuildable from MySQL,
     * since "prove you deleted it" is easier when there is one authoritative copy.
     */
    @Override
    public void deleteByScope(String scope) {
        try {
            rest.post()
                .uri("/collections/{c}/points/delete?wait=true", cfg.getCollection())
                .body(Map.of("filter", Map.of("must", List.of(
                        Map.of("key", "scope", "match", Map.of("value", scope))))))
                .retrieve().body(String.class);
            log.info("[AI-QDRANT] purged all points for scope '{}'", scope);
        } catch (Exception e) {
            log.error("[AI-QDRANT] delete by scope failed: {}", e.getMessage());
        }
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collection", cfg.getCollection());
        out.put("url", cfg.getUrl());
        try {
            JsonNode r = mapper.readTree(
                    rest.get().uri("/collections/{c}", cfg.getCollection()).retrieve().body(String.class)
            ).path("result");
            out.put("available",     true);
            out.put("pointsCount",   r.path("points_count").asLong());
            out.put("indexedVectors",r.path("indexed_vectors_count").asLong());
            out.put("status",        r.path("status").asText());
        } catch (Exception e) {
            out.put("available", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    @Override
    public boolean isAvailable() {
        try {
            rest.get().uri("/collections/{c}", cfg.getCollection()).retrieve().body(String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Float> toList(float[] v) {
        List<Float> out = new ArrayList<>(v.length);
        for (float f : v) out.add(f);
        return out;
    }
}
