package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.BillingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {
    Optional<BillingPlan> findByCode(String code);
    boolean existsByCode(String code);
    List<BillingPlan> findAllByOrderBySortOrderAscTierRankAsc();
}
