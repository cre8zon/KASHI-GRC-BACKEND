package com.kashi.grc.ai.rag;

import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiDocumentChunk;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.guardrail.PromptInjectionScanner;
import com.kashi.grc.ai.provider.EmbeddingProvider;
import com.kashi.grc.ai.repository.AiDocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The RAG read path. Query -> vector -> filtered search -> hydrated, citable context.
 *
 * ── WHY HYDRATE FROM MYSQL RATHER THAN READ THE QDRANT PAYLOAD ───────────────
 * Qdrant returns a chunkId; the text is fetched from MySQL. That is one extra
 * query and it buys three things:
 *   - the payload stays small, so index memory stays small
 *   - the text served is the current row, not a copy that may have drifted
 *   - retrievable/quarantined are re-checked against the authoritative row, so a
 *     document retired a moment ago cannot slip through a stale index entry
 *
 * ── THE CONTEXT BLOCK IS SECURITY-SENSITIVE ──────────────────────────────────
 * buildContextBlock() is where third-party text meets your prompt. Every chunk
 * is wrapped and explicitly labelled untrusted data. Concatenating retrieved
 * text straight into a prompt is the single most common way RAG systems get
 * turned against their owners — and in a TPRM product the untrusted text arrives
 * from the very party being assessed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingProvider         embeddingProvider;
    private final VectorStore               vectorStore;
    private final AiDocumentChunkRepository chunkRepository;
    private final PromptInjectionScanner    injectionScanner;
    private final AiProperties              props;

    /** A hit with everything needed to both use it and cite it. */
    public record RetrievedChunk(
            Long   chunkId,
            double score,
            String content,
            String sourceRef,
            String sectionPath,
            ChunkSourceType sourceType,
            Long   sourceId,
            boolean isGlobal
    ) {
        /** Human-readable citation for the UI's provenance panel. */
        public String citation() {
            String base = sourceRef == null ? sourceType.name() : sourceRef;
            return sectionPath == null || sectionPath.isBlank() ? base : base + " § " + sectionPath;
        }
    }

    public record RetrievalResult(List<RetrievedChunk> chunks, String contextBlock, List<Long> chunkIds) {
        public boolean isEmpty() { return chunks.isEmpty(); }
    }

    /**
     * @param tenantId    caller's tenant; null means platform-level, global only
     * @param sourceTypes narrow the corpus; null = everything the tenant may see
     */
    @Transactional(readOnly = true)
    public RetrievalResult retrieve(String query, Long tenantId,
                                    List<ChunkSourceType> sourceTypes, Integer topK) {
        if (query == null || query.isBlank()) return new RetrievalResult(List.of(), "", List.of());

        int k = topK != null ? topK : props.getRetrieval().getTopK();
        double minScore = props.getRetrieval().getMinScore();

        /*
         * THE SCOPE LIST. A tenant sees the platform corpus plus its own, and
         * nothing else. Built here, once, and passed straight into the engine
         * pre-filter. A platform admin (tenantId null) sees global only —
         * deliberately: nobody should be able to read a customer's policies
         * through an AI feature, including you.
         */
        List<String> scopes = tenantId == null
                ? List.of("global")
                : List.of("global", "t:" + tenantId);

        List<String> typeNames = sourceTypes == null ? null : sourceTypes.stream().map(Enum::name).toList();

        float[] queryVector;
        try {
            queryVector = embeddingProvider.embedOne(query);
        } catch (Exception e) {
            log.error("[AI-RETRIEVE] embedding failed, continuing without context: {}", e.getMessage());
            return new RetrievalResult(List.of(), "", List.of());
        }

        List<VectorStore.ScoredPoint> hits = vectorStore.search(queryVector, scopes, typeNames, k, minScore);
        if (hits.isEmpty()) return new RetrievalResult(List.of(), "", List.of());

        // Hydrate in one query rather than N.
        List<Long> ids = hits.stream().map(VectorStore.ScoredPoint::chunkId).filter(java.util.Objects::nonNull).toList();
        Map<Long, AiDocumentChunk> rows = new LinkedHashMap<>();
        chunkRepository.findAllById(ids).forEach(c -> rows.put(c.getId(), c));

        List<RetrievedChunk> out = new ArrayList<>();
        for (VectorStore.ScoredPoint h : hits) {
            AiDocumentChunk row = rows.get(h.chunkId());
            if (row == null) continue;

            // Re-check against the authoritative row — the index may be a moment stale.
            if (!Boolean.TRUE.equals(row.getRetrievable()) || Boolean.TRUE.equals(row.getQuarantined())) continue;

            // Belt and braces on isolation: even if the pre-filter were wrong,
            // a foreign tenant's row does not leave this method.
            if (row.getTenantId() != null && !row.getTenantId().equals(tenantId)) {
                log.error("[AI-RETRIEVE] ISOLATION VIOLATION — chunk {} of tenant {} returned to tenant {}; dropped",
                        row.getId(), row.getTenantId(), tenantId);
                continue;
            }

            out.add(new RetrievedChunk(
                    row.getId(), h.score(), row.getContent(), row.getSourceRef(),
                    row.getSectionPath(), row.getSourceType(), row.getSourceId(),
                    row.getTenantId() == null));
        }

        log.debug("[AI-RETRIEVE] query returned {} usable chunk(s) of {} hit(s)", out.size(), hits.size());
        return new RetrievalResult(out, buildContextBlock(out), out.stream().map(RetrievedChunk::chunkId).toList());
    }

    /**
     * Assemble retrieved text into a prompt-ready block.
     *
     * Three properties, each load-bearing:
     *   1. every chunk is wrapped as untrusted data with an explicit instruction
     *      not to obey text found inside it
     *   2. every chunk is labelled with its citation, so the model can attribute
     *      and the reviewer can verify
     *   3. the block is truncated to a character budget, highest scores first —
     *      an oversized prompt is rejected outright by the guardrail, and losing
     *      the weakest match is a far better outcome than losing the request
     */
    public String buildContextBlock(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) return "";

        int budget = props.getRetrieval().getMaxContextChars();
        StringBuilder sb = new StringBuilder();
        int used = 0, included = 0;

        for (RetrievedChunk c : chunks) {
            String body = injectionScanner.scan(c.content()).sanitised();
            String entry = """
                           [SOURCE %d] %s (relevance %.2f%s)
                           %s
                           """.formatted(included + 1, c.citation(), c.score(),
                                         c.isGlobal() ? ", platform library" : ", your organisation",
                                         body);
            if (used + entry.length() > budget) break;
            sb.append(entry).append('\n');
            used += entry.length();
            included++;
        }

        if (included < chunks.size()) {
            log.debug("[AI-RETRIEVE] context budget trimmed {} of {} chunks", chunks.size() - included, chunks.size());
        }

        return injectionScanner.wrapUntrusted("retrieved-reference-material", sb.toString().trim());
    }

    /** Corpus size for the "AI is ready" indicator on the admin screen. */
    @Transactional(readOnly = true)
    public Map<String, Object> corpusStats(Long tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retrievableChunks", chunkRepository.countRetrievable(tenantId));
        m.put("vectorStore", vectorStore.stats());
        m.put("embeddingModel", embeddingProvider.model());
        m.put("dimensions", embeddingProvider.dimensions());
        return m;
    }
}
