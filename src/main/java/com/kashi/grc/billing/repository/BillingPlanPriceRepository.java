package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.BillingPlanPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BillingPlanPriceRepository extends JpaRepository<BillingPlanPrice, Long> {
    List<BillingPlanPrice> findByPlanId(Long planId);
    List<BillingPlanPrice> findByPlanIdIn(Collection<Long> planIds);
    Optional<BillingPlanPrice> findByPlanIdAndCurrencyAndBillingInterval(Long planId, String currency, String billingInterval);
}
