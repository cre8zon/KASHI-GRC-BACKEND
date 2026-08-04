package com.kashi.grc.uiconfig.service;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * TenantFeatureService — the single source of truth for "does this tenant have
 * feature X enabled".
 *
 * ── WHY THIS IS THE ONE PLACE ───────────────────────────────────────────────
 * Feature entitlement is checked in three layers (nav link, frontend route
 * guard, API), and all three must agree. They agree because they all resolve
 * through here, which reads the tenant-scoped feature_flags table:
 *
 *   enabled(tenant) = flags where (tenant_id IS NULL OR tenant_id = :tenant)
 *                     AND is_enabled = true
 *
 * Global flags (tenant_id NULL) act as platform defaults; a tenant-specific row
 * can turn a feature on for just that tenant. This mirrors how nav and the
 * project library already scope by tenant.
 *
 * ── CACHING ─────────────────────────────────────────────────────────────────
 * Two layers now: a per-request ThreadLocal (unchanged — free within a single
 * request, cleared by the tenant filter at request end) in front of a
 * Redis-backed cache in TenantEntitlementCacheService (new — free ACROSS
 * requests for the same tenant, until an admin licensing change evicts it or
 * the TTL expires). Previously every request outside the ThreadLocal window
 * hit MySQL directly; this data changes only on admin action, so most of
 * those round trips were pure waste.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantFeatureService {

    private final TenantEntitlementCacheService entitlementCacheService;

    private static final ThreadLocal<Set<String>> CACHE = new ThreadLocal<>();

    /** Enabled feature keys for the current tenant. */
    public Set<String> enabledFeatures() {
        Set<String> cached = CACHE.get();
        if (cached != null) return cached;

        Long tenantId = TenantContext.getCurrentTenant();
        Set<String> keys = entitlementCacheService.getEnabledFeatureKeys(tenantId);
        CACHE.set(keys);
        return keys;
    }

    /**
     * True if the current tenant has the feature, or if featureKey is null/blank
     * (no requirement = always allowed). This null-tolerance is deliberate: most
     * routes and endpoints carry no feature requirement and must pass freely.
     */
    public boolean hasFeature(String featureKey) {
        if (featureKey == null || featureKey.isBlank()) return true;
        return enabledFeatures().contains(featureKey);
    }

    public boolean hasFeature(Long tenantId, String featureKey) {
        if (featureKey == null || featureKey.isBlank()) return true;
        return entitlementCacheService.getEnabledFeatureKeys(tenantId).contains(featureKey);
    }

    /** Called by the tenant request filter at end of request. */
    public static void clear() {
        CACHE.remove();
    }
}