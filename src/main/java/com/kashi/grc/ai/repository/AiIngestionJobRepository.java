package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiEnums.IngestionStatus;
import com.kashi.grc.ai.domain.AiIngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiIngestionJobRepository extends JpaRepository<AiIngestionJob, Long> {

    List<AiIngestionJob> findByBatchId(String batchId);

    Optional<AiIngestionJob> findTopBySourceTypeAndSourceIdOrderByCreatedAtDesc(ChunkSourceType type, Long sourceId);

    List<AiIngestionJob> findTop100ByStatusOrderByCreatedAtAsc(IngestionStatus status);

    /** Drives the admin progress bar: counts per status for one batch. */
    @Query("select j.status, count(j) from AiIngestionJob j where j.batchId = :batchId group by j.status")
    List<Object[]> batchSummary(@Param("batchId") String batchId);
}
