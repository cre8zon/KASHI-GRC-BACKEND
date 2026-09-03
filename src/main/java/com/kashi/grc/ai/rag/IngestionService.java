package com.kashi.grc.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiDocumentChunk;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiEnums.IngestionStatus;
import com.kashi.grc.ai.domain.AiIngestionJob;
import com.kashi.grc.ai.guardrail.PromptInjectionScanner;
import com.kashi.grc.ai.provider.EmbeddingProvider;
import com.kashi.grc.ai.repository.AiDocumentChunkRepository;
import com.kashi.grc.ai.repository.AiIngestionJobRepository;
import com.kashi.grc.ai.usage.AiUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source document -> chunks -> vectors -> index. The RAG write path.
 *
 * ── ORDER OF OPERATIONS MATTERS ──────────────────────────────────────────────
 *   1. hash the text and stop early if unchanged
 *   2. scan for injection and quarantine rather than index
 *   3. chunk
 *   4. embed in batches
 *   5. write chunk rows to MySQL (the truth)
 *   6. upsert vectors to Qdrant (the index)
 *
 * Step 5 before step 6 is deliberate. If Qdrant fails, the chunks still exist in
 * MySQL with indexed_at null, and the reindex sweep finds and completes them.
 * The other order loses the text entirely on a MySQL failure and leaves orphan
 * vectors pointing at rows that were never written.
 *
 * ── WHY THE HASH CHECK IS NOT AN OPTIMISATION ────────────────────────────────
 * Your policy editor autosaves every thirty seconds. Without the early exit,
 * one person editing one policy for an afternoon would re-embed that document
 * several hundred times. It is a correctness-of-spend issue, not a nicety.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final AiDocumentChunkRepository chunkRepository;
    private final AiIngestionJobRepository  jobRepository;
    private final DocumentChunker           chunker;
    private final EmbeddingProvider         embeddingProvider;
    private final VectorStore               vectorStore;
    private final PromptInjectionScanner    injectionScanner;
    private final AiUsageService            usageService;
    private final AiProperties              props;
    private final ObjectMapper              mapper;

    /** Everything one ingestion needs to know about its source. */
    public record IngestRequest(
            ChunkSourceType sourceType,
            Long   sourceId,
            String sourceRef,
            String rawText,
            boolean isHtml,
            Long   tenantId,             // null = global platform corpus
            Long   triggeredByUserId,
            String batchId,
            Map<String, Object> metadata
    ) {}

    public record IngestResult(Long jobId, IngestionStatus status, int chunksCreated, String message) {}

    // ── Main entry point ──────────────────────────────────────────────────────

    @Transactional
    public IngestResult ingest(IngestRequest req) {
        long started = System.currentTimeMillis();

        AiIngestionJob job = jobRepository.save(AiIngestionJob.builder()
                .sourceType(req.sourceType()).sourceId(req.sourceId()).sourceRef(req.sourceRef())
                .status(IngestionStatus.EXTRACTING).batchId(req.batchId())
                .triggeredByUserId(req.triggeredByUserId()).startedAt(LocalDateTime.now())
                .tenantId(req.tenantId()).build());

        try {
            // 1 ── extract
            String text = req.isHtml() ? chunker.stripHtml(req.rawText()) : req.rawText();
            if (text == null || text.isBlank()) return finish(job, IngestionStatus.SKIPPED_UNCHANGED, 0, started, "empty source");

            job.setCharactersExtracted(text.length());
            String hash = sha256(text);
            job.setContentHash(hash);

            // 2 ── unchanged? stop before spending anything
            if (chunkRepository.countMatchingHash(req.sourceType(), req.sourceId(), hash) > 0) {
                log.debug("[AI-INGEST] {}#{} unchanged — skipped", req.sourceType(), req.sourceId());
                return finish(job, IngestionStatus.SKIPPED_UNCHANGED, 0, started, "content unchanged");
            }

            // 3 ── injection scan. Quarantine beats indexing beats rejecting.
            var scan = injectionScanner.scan(text);
            if (scan.suspicious()) {
                log.warn("[AI-INGEST] quarantined {}#{} — {}", req.sourceType(), req.sourceId(), scan.reasonSummary());
                job.setQuarantined(true);
                persistQuarantined(req, scan.sanitised(), hash, scan.reasonSummary());
                return finish(job, IngestionStatus.COMPLETED, 0, started,
                        "quarantined: " + scan.reasonSummary());
            }
            text = scan.sanitised();

            // 4 ── chunk
            job.setStatus(IngestionStatus.CHUNKING);
            List<DocumentChunker.Chunk> chunks = chunker.chunk(text, false);
            if (chunks.isEmpty()) return finish(job, IngestionStatus.SKIPPED_UNCHANGED, 0, started, "no chunks produced");

            // 5 ── replace: delete the old generation before writing the new one.
            //      Otherwise an edit that shortens a policy leaves orphan chunks
            //      of deleted text that retrieval will happily surface forever.
            int deleted = chunkRepository.deleteBySource(req.sourceType(), req.sourceId());
            vectorStore.deleteBySource(req.sourceType().name(), req.sourceId());
            job.setChunksDeleted(deleted);

            // 6 ── embed
            job.setStatus(IngestionStatus.EMBEDDING);
            vectorStore.ensureCollection(embeddingProvider.dimensions());

            List<String> texts = chunks.stream().map(DocumentChunker.Chunk::content).toList();
            List<float[]> vectors = embeddingProvider.embed(texts);
            int embeddingTokens = embeddingProvider.estimateTokens(texts);
            usageService.record(req.tenantId(), 0, 0, embeddingTokens, 0L, false, false);

            // 7 ── persist chunk rows (MySQL is the truth)
            job.setStatus(IngestionStatus.INDEXING);
            String scope = req.tenantId() == null ? "global" : "t:" + req.tenantId();
            List<AiDocumentChunk> saved = new ArrayList<>(chunks.size());
            List<VectorStore.VectorPoint> points = new ArrayList<>(chunks.size());

            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunker.Chunk c = chunks.get(i);
                String vectorId = UUID.randomUUID().toString();

                AiDocumentChunk row = chunkRepository.save(AiDocumentChunk.builder()
                        .sourceType(req.sourceType()).sourceId(req.sourceId()).sourceRef(req.sourceRef())
                        .sectionPath(c.sectionPath()).chunkIndex(c.index())
                        .content(c.content()).contentHash(hash).tokenEstimate(c.estimatedTokens())
                        .vectorId(vectorId)
                        .embeddingModel(embeddingProvider.model())
                        .embeddingDimensions(embeddingProvider.dimensions())
                        .tenantScope(scope)
                        .metadataJson(writeJson(req.metadata()))
                        .retrievable(true).quarantined(false)
                        .tenantId(req.tenantId())
                        .build());
                saved.add(row);

                Map<String, Object> payload = new LinkedHashMap<>();
                if (req.metadata() != null) payload.putAll(req.metadata());
                payload.put("sourceRef",   req.sourceRef());
                payload.put("sectionPath", c.sectionPath());
                payload.put("chunkIndex",  c.index());

                points.add(new VectorStore.VectorPoint(
                        vectorId, vectors.get(i), scope,
                        req.sourceType().name(), req.sourceId(), row.getId(), payload));
            }

            // 8 ── index
            vectorStore.upsert(points);

            LocalDateTime now = LocalDateTime.now();
            saved.forEach(r -> r.setIndexedAt(now));
            chunkRepository.saveAll(saved);

            job.setTokensEmbedded(embeddingTokens);
            log.info("[AI-INGEST] {}#{} indexed | chunks={} deleted={} tokens={} scope={}",
                    req.sourceType(), req.sourceId(), saved.size(), deleted, embeddingTokens, scope);

            return finish(job, IngestionStatus.COMPLETED, saved.size(), started, null);

        } catch (Exception e) {
            log.error("[AI-INGEST] {}#{} failed", req.sourceType(), req.sourceId(), e);
            job.setErrorCode("INGEST_FAILED");
            job.setErrorMessage(e.getMessage());
            return finish(job, IngestionStatus.FAILED, 0, started, e.getMessage());
        }
    }

    /*
     * NOTE: the async entry point deliberately lives in IngestionAsyncFacade,
     * not here. An @Async method on this class calling ingest() would be a
     * self-invocation and would bypass the @Transactional proxy — see that
     * class for why that failure mode is worth a separate bean.
     */

    /** Retire a source's chunks without deleting them — for a DEPRECATED policy. */
    @Transactional
    public void retire(ChunkSourceType type, Long sourceId) {
        chunkRepository.setRetrievable(type, sourceId, false);
        vectorStore.deleteBySource(type.name(), sourceId);
        log.info("[AI-INGEST] retired {}#{} from retrieval (rows kept for provenance)", type, sourceId);
    }

    /** Hard delete, for erasure requests. */
    @Transactional
    public void purge(ChunkSourceType type, Long sourceId) {
        chunkRepository.deleteBySource(type, sourceId);
        vectorStore.deleteBySource(type.name(), sourceId);
    }

    /**
     * Re-embed everything built with a stale model or dimension. Run after
     * changing app.ai.embedding.* — the reason those two columns are on the
     * chunk row in the first place.
     */
    @Transactional
    public void reindexStale() {
        List<AiDocumentChunk> stale = chunkRepository.findStaleVectors(
                embeddingProvider.model(), embeddingProvider.dimensions());
        if (stale.isEmpty()) { log.info("[AI-INGEST] reindex: nothing stale"); return; }

        log.info("[AI-INGEST] reindex: {} stale chunk(s)", stale.size());
        vectorStore.ensureCollection(embeddingProvider.dimensions());

        int batch = props.getEmbedding().getBatchSize();
        for (int i = 0; i < stale.size(); i += batch) {
            List<AiDocumentChunk> slice = stale.subList(i, Math.min(stale.size(), i + batch));
            List<float[]> vectors = embeddingProvider.embed(slice.stream().map(AiDocumentChunk::getContent).toList());

            List<VectorStore.VectorPoint> points = new ArrayList<>();
            for (int j = 0; j < slice.size(); j++) {
                AiDocumentChunk c = slice.get(j);
                if (c.getVectorId() == null) c.setVectorId(UUID.randomUUID().toString());
                c.setEmbeddingModel(embeddingProvider.model());
                c.setEmbeddingDimensions(embeddingProvider.dimensions());
                c.setIndexedAt(LocalDateTime.now());
                points.add(new VectorStore.VectorPoint(
                        c.getVectorId(), vectors.get(j), c.getTenantScope(),
                        c.getSourceType().name(), c.getSourceId(), c.getId(),
                        Map.of("sourceRef", String.valueOf(c.getSourceRef()),
                               "sectionPath", String.valueOf(c.getSectionPath()))));
            }
            vectorStore.upsert(points);
            chunkRepository.saveAll(slice);
            log.info("[AI-INGEST] reindexed {}/{}", Math.min(stale.size(), i + batch), stale.size());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Quarantined content is stored but never indexed. Keeping it is not
     * squeamishness — you want the evidence of what was attempted, and the
     * admin screen needs something to show when it says a document was rejected.
     */
    private void persistQuarantined(IngestRequest req, String text, String hash, String reason) {
        chunkRepository.deleteBySource(req.sourceType(), req.sourceId());
        chunkRepository.save(AiDocumentChunk.builder()
                .sourceType(req.sourceType()).sourceId(req.sourceId()).sourceRef(req.sourceRef())
                .chunkIndex(0).content(text.length() > 60000 ? text.substring(0, 60000) : text)
                .contentHash(hash).tokenEstimate(chunker.estimateTokens(text))
                .tenantScope(req.tenantId() == null ? "global" : "t:" + req.tenantId())
                .retrievable(false).quarantined(true).quarantineReason(truncate(reason, 300))
                .tenantId(req.tenantId()).build());
    }

    private IngestResult finish(AiIngestionJob job, IngestionStatus status, int chunks, long started, String msg) {
        job.setStatus(status);
        job.setChunksCreated(chunks);
        job.setFinishedAt(LocalDateTime.now());
        job.setDurationMs(System.currentTimeMillis() - started);
        if (msg != null && job.getErrorMessage() == null) job.setErrorMessage(truncate(msg, 1000));
        jobRepository.save(job);
        return new IngestResult(job.getId(), status, chunks, msg);
    }

    private String writeJson(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        try { return mapper.writeValueAsString(m); } catch (Exception e) { return null; }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }
}
