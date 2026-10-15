package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.BillingFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingFeatureRepository extends JpaRepository<BillingFeature, Long> {
    Optional<BillingFeature> findByFeatureKey(String featureKey);
    boolean existsByFeatureKey(String featureKey);
    List<BillingFeature> findAllByOrderBySortOrderAscIdAsc();
}
