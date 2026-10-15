package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.TenantSubscription;

import java.time.LocalDate;
import java.util.List;

public interface TenantSubscriptionRepositoryCustom {

    /** The rows live for one tenant on a day, latest-starting first — the head of its chain. */
    List<TenantSubscription> findLiveOn(Long tenantId, LocalDate day);

    /** Every row live on a day, all tenants. */
    List<TenantSubscription> findAllLiveOn(LocalDate day);

    /** Live-status rows whose term ended on or before :day — candidates for replace / auto-renew / churn. */
    List<TenantSubscription> findLapsed(LocalDate day);

    /** Paid rows whose term ends in (from, to], soonest first. */
    List<TenantSubscription> findRenewingBetween(LocalDate from, LocalDate to);
}