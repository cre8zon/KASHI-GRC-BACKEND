package com.kashi.grc.common.cache;

import com.kashi.grc.uiconfig.service.TenantEntitlementCacheService;
import com.kashi.grc.ucf.service.TagExpansionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pre-populates cache entries at startup so the first wave of requests after
 * a deploy/restart doesn't all pay the cold-cache MySQL cost together.
 *
 * ── SCOPE: WHY ONLY THESE TWO CACHES ────────────────────────────────────────
 * Only caches that can be loaded WITHOUT a real authenticated request context
 * are warmed here:
 *
 *   - UCF catalogue: global, tenant-independent, no user context needed at
 *     all — always safe to warm unconditionally.
 *   - Tenant entitlements: TenantEntitlementCacheService.getEnabledFeatureKeys
 *     takes tenantId as a plain parameter, so it can be called directly for a
 *     configured list of tenant ids — no request/security context needed.
 *
 * UI config (forms/screens/actions/dashboard widgets) is deliberately NOT
 * warmed here. Every one of those methods derives tenantId from
 * UtilityService.getLoggedInDataContext(), which reads the authenticated
 * principal — there is no real principal at application-startup time. Faking
 * one to force those methods to run would risk warming a cache entry that
 * doesn't match what a REAL request for that tenant would compute (e.g.
 * getScreenConfig's permission/side filtering depends on the actual calling
 * user, not just the tenant). Warming those correctly would need those
 * methods refactored to accept an explicit tenantId parameter instead of
 * pulling it from request context — a real, separate, well-scoped follow-up,
 * not something to fake here.
 *
 * Configure which tenants to warm via kashi.cache.warmup.tenant-ids (comma-
 * separated). Empty/unset = skip tenant-entitlement warming entirely (safe
 * default — nothing breaks if you don't configure this).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.redis.enabled", havingValue = "true")
public class CacheWarmupRunner {

    private final TagExpansionService tagExpansionService;
    private final TenantEntitlementCacheService entitlementCacheService;

    @Value("${kashi.cache.warmup.tenant-ids:}")
    private String warmupTenantIdsCsv;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            tagExpansionService.warmUp();
        } catch (Exception e) {
            // Never let a warmup failure block app startup — worst case the
            // cache just stays cold and populates lazily on first real request,
            // which is exactly the pre-warming behavior anyway.
            log.warn("[CACHE-WARMUP] UCF catalogue warmup failed, will load lazily on first use — {}",
                    e.toString());
        }

        List<Long> tenantIds = parseTenantIds(warmupTenantIdsCsv);
        if (tenantIds.isEmpty()) {
            log.info("[CACHE-WARMUP] No kashi.cache.warmup.tenant-ids configured — skipping tenant entitlement warmup");
            return;
        }
        int warmed = 0;
        for (Long tenantId : tenantIds) {
            try {
                entitlementCacheService.getEnabledFeatureKeys(tenantId);
                warmed++;
            } catch (Exception e) {
                log.warn("[CACHE-WARMUP] Entitlement warmup failed for tenantId={}, will load lazily — {}",
                        tenantId, e.toString());
            }
        }
        log.info("[CACHE-WARMUP] Warmed tenant entitlements for {}/{} configured tenants", warmed, tenantIds.size());
    }

    private List<Long> parseTenantIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }
}