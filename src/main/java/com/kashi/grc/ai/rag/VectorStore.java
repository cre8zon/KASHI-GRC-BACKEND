package com.kashi.grc.ai.rag;

import java.util.List;
import java.util.Map;

/**
 * The vector index, behind an interface.
 *
 * ── WHY AN INTERFACE WHEN YOU HAVE ALREADY CHOSEN QDRANT ─────────────────────
 * Because the choice is not really final and pretending otherwise is expensive
 * later. A self-hosting customer may refuse a second datastore. A regulated one
 * may require the index inside their own VPC. And Qdrant is not the last word in
 * this space. One interface plus one implementation costs nothing today and
 * makes each of those a new class rather than a refactor of every call site.
 *
 * ── THE TENANT FILTER IS PART OF THE CONTRACT ────────────────────────────────
 * search() takes tenantScope tokens and every implementation MUST apply them as
 * a PRE-filter, inside the engine, before scoring. Retrieving broadly and
 * filtering in Java looks equivalent and is not: it is slower, it silently
 * degrades recall as the corpus grows, and the day someone forgets the filter it
 * leaks one tenant's policies into another's generated document. In a compliance
 * product that is not a bug report, it is a breach notification.
 */
public interface VectorStore {

    /** A point on its way in. `scope` is the filter token: "global" or "t:{id}". */
    record VectorPoint(
            String id,                    // UUID, matches AiDocumentChunk.vectorId
            float[] vector,
            String scope,
            String sourceType,
            Long   sourceId,
            Long   chunkId,               // back-reference to AiDocumentChunk.id
            Map<String, Object> payload
    ) {}

    /** A hit on its way out. `chunkId` is what makes provenance recoverable. */
    record ScoredPoint(
            String id,
            double score,
            Long   chunkId,
            String sourceType,
            Long   sourceId,
            Map<String, Object> payload
    ) {}

    /** Create the collection if absent. Idempotent. */
    void ensureCollection(int dimensions);

    void upsert(List<VectorPoint> points);

    /**
     * Similarity search.
     *
     * @param scopes      allowed tenant tokens, e.g. ["global", "t:42"] — applied
     *                    as a hard pre-filter, never after scoring
     * @param sourceTypes optional ChunkSourceType names to narrow to; null = all
     * @param minScore    hits below this are dropped by the caller
     */
    List<ScoredPoint> search(float[] queryVector, List<String> scopes,
                             List<String> sourceTypes, int topK, double minScore);

    void deleteByIds(List<String> vectorIds);

    void deleteBySource(String sourceType, Long sourceId);

    /** Drop everything for one tenant. Needed for offboarding and GDPR/DPDP erasure. */
    void deleteByScope(String scope);

    /** Health and size, for the admin screen and the actuator indicator. */
    Map<String, Object> stats();

    boolean isAvailable();
}
