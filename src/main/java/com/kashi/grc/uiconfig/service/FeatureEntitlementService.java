package com.kashi.grc.uiconfig.service;

import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.uiconfig.domain.FeatureFlag;
import com.kashi.grc.uiconfig.repository.FeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns the GLOBAL ⇄ LICENSED lifecycle for feature entitlements, keeping the
 * invariant that a feature is in exactly ONE mode at a time (never both).
 *
 * The mode lives on the GLOBAL catalogue row (tenant_id = null). Tenant rows are
 * the grants while LICENSED. Transitions soft-delete the outgoing row-set rather
 * than hard-deleting it, so licensing history (who had what, when) is preserved
 * and re-licensing restores prior state.
 *
 * All transitions run in one transaction so the "soft-delete the other side"
 * step can never be skipped — that is the structural guarantee against the
 * multiple-active-rows bug.
 */
@Service
@RequiredArgsConstructor
public class FeatureEntitlementService {

    private final FeatureFlagRepository featureFlagRepository;

    /** Ensure a global catalogue row exists for a key; returns it. */
    private FeatureFlag globalRow(String flagKey, String description) {
        return featureFlagRepository.findByFlagKeyAndTenantIdIsNull(flagKey)
                .orElseGet(() -> FeatureFlag.builder()
                        .flagKey(flagKey).description(description)
                        .tenantId(null).mode("GLOBAL").isEnabled(false)
                        .build());
    }

    /**
     * Put a feature in GLOBAL mode: the global row's enabled flag decides for
     * everyone. Soft-deletes every active tenant row (history retained).
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.TENANT_ENTITLEMENTS, allEntries = true)
    public void setGlobal(String flagKey, boolean enabled, Long actorUserId) {
        FeatureFlag global = globalRow(flagKey, null);
        global.setMode("GLOBAL");
        global.setEnabled(enabled);
        global.setDeletedAt(null);
        global.setDeletedBy(null);
        featureFlagRepository.save(global);

        // Retire any active tenant rows — soft delete, not hard delete.
        List<FeatureFlag> tenantRows = featureFlagRepository.findByFlagKey(flagKey).stream()
                .filter(f -> f.getTenantId() != null && f.getDeletedAt() == null)
                .toList();
        for (FeatureFlag t : tenantRows) {
            t.setDeletedAt(LocalDateTime.now());
            t.setDeletedBy(actorUserId);
            featureFlagRepository.save(t);
        }
    }

    /**
     * Put a feature in LICENSED mode: per-tenant rows decide; the global row
     * grants nothing. Does not create tenant rows — use setTenant afterwards.
     * The global row is kept (mode marker + catalogue definition) but inert.
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.TENANT_ENTITLEMENTS, allEntries = true)
    public void setLicensed(String flagKey, Long actorUserId) {
        FeatureFlag global = globalRow(flagKey, null);
        global.setMode("LICENSED");
        // Global row grants nothing while licensed; leave isEnabled but the
        // resolver ignores it in LICENSED mode. Keep it active as the catalogue row.
        global.setDeletedAt(null);
        featureFlagRepository.save(global);
    }

    /**
     * Enable/disable a feature for one tenant. Valid only while the feature is
     * LICENSED. Restores a soft-deleted row if re-licensing that tenant.
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.TENANT_ENTITLEMENTS, allEntries = true)
    public void setTenant(String flagKey, Long tenantId, boolean enabled, Long actorUserId) {
        FeatureFlag global = featureFlagRepository.findByFlagKeyAndTenantIdIsNull(flagKey)
                .orElse(null);
        String mode = (global != null && global.getMode() != null) ? global.getMode() : "GLOBAL";
        if (!"LICENSED".equals(mode)) {
            throw new IllegalStateException(
                    "Feature '" + flagKey + "' is GLOBAL. Switch it to LICENSED before "
                            + "setting per-tenant entitlement.");
        }

        FeatureFlag row = featureFlagRepository.findByFlagKeyAndTenantId(flagKey, tenantId)
                .orElseGet(() -> FeatureFlag.builder()
                        .flagKey(flagKey)
                        .description(global != null ? global.getDescription() : null)
                        .tenantId(tenantId).mode(null)
                        .build());
        row.setEnabled(enabled);
        row.setDeletedAt(null);   // un-soft-delete on re-license
        row.setDeletedBy(null);
        featureFlagRepository.save(row);
    }
}