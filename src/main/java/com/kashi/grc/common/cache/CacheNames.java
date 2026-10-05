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

    /**
     * Tenant-level /stats payloads behind the dashboards.
     *
     * These are the expensive ones: the audit stats endpoint scans every
     * finding and control instance in the tenant, and eleven widgets read it.
     * The frontend now shares one request per endpoint, so this is the second
     * line of defence — across users, tabs and page reloads.
     *
     * Short TTL on purpose. A dashboard number two minutes stale is fine; one
     * ten minutes stale gets mistrusted, and a mistrusted dashboard is not
     * looked at.
     */
    public static final String DASHBOARD_STATS = "dashboardStats";

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

    // ── Audit template / workflow dropdown listings — hit on every "New
    // engagement" form open (search-as-you-type), backed by data that only
    // changes when an admin publishes/edits a template or workflow blueprint.
    // See AuditReferenceListCacheService.
    public static final String AUDIT_TEMPLATE_LIST = "auditTemplateList";
    public static final String WORKFLOW_BLUEPRINT_LIST = "workflowBlueprintList";

    /**
     * Library list endpoints. These were never cached — every policy/test list
     * request went to the database, and with ~250ms of round-trip latency to
     * Aiven that is the floor no projection can get under.
     *
     * Cached values are List<Map<String,Object>> deliberately, NOT the summary
     * records: the Redis serializer uses DefaultTyping.NON_FINAL, records are
     * final, so no @class marker is written and they would deserialise as
     * LinkedHashMap and fail on cast. Maps are also exactly what the endpoint
     * returns, so nothing is converted twice.
     */
    public static final String AUDIT_POLICY_LIST = "auditPolicyList";
    public static final String AUDIT_TEST_LIST   = "auditTestList";

    /**
     * In-flight progress for an assessment snapshot. See
     * AssessmentProgressTracker.
     *
     * The odd one out in this file: every other region here caches a READ to
     * save a database round trip, and losing an entry costs a slow request.
     * This one is a side channel for a WRITE that has not committed yet.
     * executeAssessment builds the whole instance tree in one transaction, so
     * nothing it writes is visible until it finishes — Redis is the only place
     * a progress figure can go where a polling client can actually see it.
     *
     * Losing an entry here costs a progress bar, never data: the snapshot is
     * transactional and completes regardless.
     *
     * Values are Map<String,Object>, for exactly the reason given for the two
     * list caches above — the serializer writes no @class for a final type, so
     * a record would come back as a LinkedHashMap and fail on cast.
     *
     * Written directly via cache.put rather than @Cacheable, so
     * TenantAwareKeyGenerator does not apply; the key carries its own tenant
     * prefix. See AssessmentProgressTracker.keyFor.
     */
    public static final String ASSESSMENT_PROGRESS = "assessmentProgress";
}