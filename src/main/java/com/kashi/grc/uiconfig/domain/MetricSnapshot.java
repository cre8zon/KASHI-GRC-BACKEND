package com.kashi.grc.uiconfig.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One metric, one tenant, one day.
 *
 * History has to be recorded as it happens. The live tables hold the CURRENT
 * state — how many findings are open now — and nothing anywhere knows how many
 * were open in March. A trend chart cannot be computed retroactively, so the
 * capture starts before the charts exist rather than after.
 */
@Entity
@Table(
        name = "dashboard_metric_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_metric_day", columnNames = {"tenant_id", "metric_key", "captured_on"}),
        indexes = @Index(name = "idx_metric_series", columnList = "tenant_id,metric_key,captured_on")
)
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class MetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** '<module>.<key>' — 'incidents.open', 'audit.openFindings'. */
    @Column(name = "metric_key", nullable = false, length = 100)
    private String metricKey;

    @Column(name = "captured_on", nullable = false)
    private LocalDate capturedOn;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal value;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}