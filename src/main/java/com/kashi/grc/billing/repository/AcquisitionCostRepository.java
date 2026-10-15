package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.AcquisitionCost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcquisitionCostRepository extends JpaRepository<AcquisitionCost, Long> {
    List<AcquisitionCost> findAllByOrderByPeriodMonthDescIdDesc();
    List<AcquisitionCost> findByPeriodMonthBetween(String fromInclusive, String toInclusive);
}
