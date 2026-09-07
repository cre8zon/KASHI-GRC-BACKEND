package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.AiDocumentChunk;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiDocumentChunkRepository extends JpaRepository<AiDocumentChunk, Long> {

    List<AiDocumentChunk> findBySourceTypeAndSourceIdOrderByChunkIndexAsc(ChunkSourceType sourceType, Long sourceId);

    List<AiDocumentChunk> findByVectorIdIn(List<String> vectorIds);

    /**
     * Hash check that makes re-ingestion free. The editor autosaves every thirty
     * seconds; without this the same policy would be re-embedded all day.
     */
    @Query("select count(c) from AiDocumentChunk c where c.sourceType = :type and c.sourceId = :id and c.contentHash = :hash")
    long countMatchingHash(@Param("type") ChunkSourceType type, @Param("id") Long id, @Param("hash") String hash);

    @Modifying
    @Query("delete from AiDocumentChunk c where c.sourceType = :type and c.sourceId = :id")
    int deleteBySource(@Param("type") ChunkSourceType type, @Param("id") Long id);

    /**
     * Rows whose vector was built with a now-stale embedding model or dimension.
     * Drives the re-index sweep after a model change — the reason those two
     * columns exist on the chunk at all.
     */
    @Query("""
           select c from AiDocumentChunk c
           where c.quarantined = false
             and (c.embeddingModel <> :model or c.embeddingDimensions <> :dims or c.indexedAt is null)
           """)
    List<AiDocumentChunk> findStaleVectors(@Param("model") String model, @Param("dims") Integer dims);

    @Query("select count(c) from AiDocumentChunk c where (c.tenantId = :tenantId or c.tenantId is null) and c.retrievable = true and c.quarantined = false")
    long countRetrievable(@Param("tenantId") Long tenantId);

    /** Bulk retirement when a policy is deprecated — keep for provenance, stop grounding new work in it. */
    @Modifying
    @Query("update AiDocumentChunk c set c.retrievable = :retrievable where c.sourceType = :type and c.sourceId = :id")
    int setRetrievable(@Param("type") ChunkSourceType type, @Param("id") Long id, @Param("retrievable") boolean retrievable);
}
