package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.TenantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Date-window queries (live on a day, lapsed, renewing) live in the Custom fragment (Criteria API). */
public interface TenantSubscriptionRepository
        extends JpaRepository<TenantSubscription, Long>, TenantSubscriptionRepositoryCustom {

    List<TenantSubscription> findByTenantIdOrderByTermStartDescIdDesc(Long tenantId);

    List<TenantSubscription> findByStatusIn(Collection<String> statuses);

    Optional<TenantSubscription> findFirstByPreviousSubscriptionId(Long previousSubscriptionId);

    boolean existsByTenantIdAndStatusIn(Long tenantId, Collection<String> statuses);
}