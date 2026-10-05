package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    Optional<MetricSnapshot> findByTenantIdAndMetricKeyAndCapturedOn(
            Long tenantId, String metricKey, LocalDate capturedOn);

    /** A series, for the trend endpoint when it is built. */
    List<MetricSnapshot> findByTenantIdAndMetricKeyAndCapturedOnBetweenOrderByCapturedOnAsc(
            Long tenantId, String metricKey, LocalDate from, LocalDate to);
}