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

    /**
     * The result of the previous run of this same check, or null for the first.
     *
     * Continuous monitoring is not "we collect evidence often" — it is knowing
     * the moment a control stops holding. Without the previous value there is
     * nothing to compare against, and drift is invisible until someone reads a
     * dashboard.
     */
    @Column(name = "previous_result", length = 10)
    private String previousResult;

    /**
     * True when result differs from previousResult. Denormalised so "what broke
     * this week" is an indexed query rather than a self-join over run history.
     */
    @Column(name = "result_changed")
    @Builder.Default
    private Boolean resultChanged = false;

    /** PASS→FAIL | FAIL→PASS | null when unchanged or first run. */
    @Column(name = "transition", length = 20)
    private String transition;
}