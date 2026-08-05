package com.kashi.grc.common.cache;

/**
 * Central registry of Redis cache-region names. Mirrors the KafkaTopics
 * convention: never inline a cache name string at a call site, always
 * reference a constant here so a rename or eviction sweep only touches
 * one file.
 *
 * Each name maps 1:1 to a TTL entry configured in CacheConfig — adding a
 * new cache name here without adding it to CacheConfig's per-cache map
 * just falls back to the 5-minute default, which is fine for most
 * reference/config data but should be a deliberate choice, not an
 * accident. Check CacheConfig when adding one.
 */
public final class CacheNames {

    private CacheNames() {}

    // ── UI config / dynamic forms — read on nearly every screen/form load,
    // written only from admin screens. See UiConfigServiceImpl + UiAdminController.
    public static final String UI_FORM      = "uiForm";
    public static final String UI_SCREEN    = "uiScreen";
    public static final String UI_ACTIONS   = "uiActions";
    public static final String UI_DASHBOARD = "uiDashboardWidgets";

    // ── Reference/lookup data — user-facing display names resolved on every
    // history/assignment screen. See UserDisplayNameService.
    public static final String USER_DISPLAY_NAME = "userDisplayName";

    // ── UCF catalogue — promoted from TagExpansionService's in-process
    // AtomicReference cache. Global (not tenant-scoped): the catalogue is
    // shared across all tenants, so this is the one cache region that
    // deliberately does NOT go through TenantAwareKeyGenerator's per-tenant
    // prefixing (see TagExpansionService for how the key is built).
    public static final String UCF_CATALOGUE = "ucfCatalogue";

    // ── Tenant feature entitlements — checked on most authenticated requests.
    public static final String TENANT_ENTITLEMENTS = "tenantEntitlements";

    // ── Assessment template structure (sections + questions + options, the
    // full library snapshot needed by instantiation) — read on every
    // assessment/audit creation, changes only when an admin edits a template
    // in the library. See AssessmentTemplateStructureCacheService.
    public static final String ASSESSMENT_TEMPLATE_STRUCTURE = "assessmentTemplateStructure";
}