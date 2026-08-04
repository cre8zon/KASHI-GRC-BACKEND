package com.kashi.grc.uiconfig.service;

import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.uiconfig.domain.FeatureFlag;
import com.kashi.grc.uiconfig.repository.FeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cache-only wrapper around FeatureFlagRepository.findEnabledForTenant().
 *
 * WHY A SEPARATE CLASS: TenantFeatureService.enabledFeatures() and
 * hasFeature() call each other internally (self-invocation). Spring's
 * @Cacheable is implemented as an AOP proxy wrapper — a method calling
 * "this.otherMethod()" never goes through that proxy, so annotating a method
 * on TenantFeatureService itself would silently never fire for the most
 * common call path (hasFeature -> enabledFeatures). Splitting the
 * DB-fetching part into its own bean means the call from TenantFeatureService
 * into here IS a real inter-bean call, so the proxy — and the cache —
 * actually applies.
 *
 * This is checked on nearly every authenticated request (nav, route guards,
 * API checks all resolve through TenantFeatureService), so it's exactly the
 * kind of read-heavy/write-rare data Redis is for. Eviction is wired from
 * FeatureEntitlementService's three admin mutation methods (setGlobal,
 * setLicensed, setTenant) — see @CacheEvict there — so an admin's licensing
 * change is visible immediately, with the TTL only as a safety net.
 */
@Service
@RequiredArgsConstructor
public class TenantEntitlementCacheService {

    private final FeatureFlagRepository featureFlagRepository;

    @Cacheable(cacheNames = CacheNames.TENANT_ENTITLEMENTS)
    public Set<String> getEnabledFeatureKeys(Long tenantId) {
        return featureFlagRepository.findEnabledForTenant(tenantId).stream()
                .map(FeatureFlag::getFlagKey)
                .collect(Collectors.toSet());
    }
}