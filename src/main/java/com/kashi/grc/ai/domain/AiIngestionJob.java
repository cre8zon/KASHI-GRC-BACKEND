package com.kashi.grc.ai.domain;

import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiEnums.IngestionStatus;
import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AiIngestionJob — one attempt to turn a source document into retrievable chunks.
 *
 * ── WHY TRACK THIS AT ALL ────────────────────────────────────────────────────
 * Ingestion is the step that silently fails. A scanned PDF yields no text, an
 * embedding call rate-limits halfway through, a 400-page vendor report blows the
 * batch budget. Without a job row the only symptom is retrieval quietly getting
 * worse, which is the hardest class of bug to notice and the easiest to blame on
 * "the AI".
 *
 * A row per attempt makes the failure visible, makes retry idempotent, and gives
 * the admin screen something honest to show: "412 of 460 documents indexed,
 * 3 failed, 45 skipped as unchanged".
 *
 * ── IDEMPOTENCY ──────────────────────────────────────────────────────────────
 * Re-running against an unchanged source lands on SKIPPED_UNCHANGED via the
 * content hash and costs nothing. Safe to schedule as a nightly sweep, which is
 * exactly what you want once the corpus is real.
 */
@Entity
@Table(name = "ai_ingestion_jobs",
        indexes = {
                @Index(name = "idx_aij_tenant", columnList = "tenant_id"),
                @Index(name = "idx_aij_source", columnList = "source_type,source_id"),
                @Index(name = "idx_aij_status", columnList = "status"),
                @Index(name = "idx_aij_batch",  columnList = "batch_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AiIngestionJob extends GlobalOrTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private ChunkSourceType sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private IngestionStatus status = IngestionStatus.PENDING;

    /** Groups jobs from one bulk sweep so the admin screen can show a single progress bar. */
    @Column(name = "batch_id", length = 40)
    private String batchId;

    @Column(name = "chunks_created")  private Integer chunksCreated;
    @Column(name = "chunks_deleted")  private Integer chunksDeleted;
    @Column(name = "tokens_embedded") private Integer tokensEmbedded;
    @Column(name = "characters_extracted") private Integer charactersExtracted;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "started_at")  private LocalDateTime startedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
    @Column(name = "duration_ms") private Long durationMs;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "triggered_by_user_id")
    private Long triggeredByUserId;

    /** Set when the injection scanner quarantined the source rather than indexing it. */
    @Column(name = "quarantined", nullable = false)
    @Builder.Default
    private Boolean quarantined = false;
}
