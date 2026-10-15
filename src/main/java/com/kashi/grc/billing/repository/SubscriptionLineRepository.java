package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.SubscriptionLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SubscriptionLineRepository extends JpaRepository<SubscriptionLine, Long> {
    List<SubscriptionLine> findBySubscriptionIdOrderByIdAsc(Long subscriptionId);
    List<SubscriptionLine> findBySubscriptionIdIn(Collection<Long> subscriptionIds);
    void deleteBySubscriptionId(Long subscriptionId);
}
