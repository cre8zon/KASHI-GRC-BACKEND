package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.TenantMrrSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TenantMrrSnapshotRepository extends JpaRepository<TenantMrrSnapshot, Long> {
    List<TenantMrrSnapshot> findByPeriodMonth(String periodMonth);
    List<TenantMrrSnapshot> findByPeriodMonthIn(Collection<String> periodMonths);
    List<TenantMrrSnapshot> findByTenantIdOrderByPeriodMonthAsc(Long tenantId);
    boolean existsByPeriodMonth(String periodMonth);
    boolean existsByTenantIdAndPeriodMonth(Long tenantId, String periodMonth);
}
