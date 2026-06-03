package com.kashi.grc.integration.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * IntegrationRun — immutable record of one check execution.
 *
 * Created by IntegrationRunner after each check run.
 * Never mutated after creation — audit trail only.
 */
@Entity
@Table(name = "integration_runs",
        indexes = {
                @Index(name = "idx_ir_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_ir_config",  columnList = "integration_config_id"),
                @Index(name = "idx_ir_check",   columnList = "check_key"),
                @Index(name = "idx_ir_run_at",  columnList = "run_at")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class IntegrationRun extends TenantAwareEntity {

    @Column(name = "integration_config_id", nullable = false)
    private Long integrationConfigId;

    @Column(name = "check_key", nullable = false, length = 100)
    private String checkKey;

    @Column(name = "control_tag", nullable = false, length = 80)
    private String controlTag;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    @Column(name = "status", nullable = false, length = 10)
    private String status; // SUCCESS | FAILURE | PARTIAL | SKIPPED

    @Column(name = "result", length = 10)
    private String result; // PASS | FAIL | ERROR

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    /** EvidenceRecord created by this run — null if run failed before creating it */
    @Column(name = "evidence_record_id")
    private Long evidenceRecordId;

    @Column(name = "raw_payload", columnDefinition = "LONGTEXT")
    private String rawPayload;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;
}