package com.kashi.grc.uiconfig.security;

import java.lang.annotation.*;

/**
 * Declares that an endpoint (or every endpoint in a controller) requires the
 * current tenant to have a feature enabled. Enforced by RequiresFeatureAspect,
 * which returns 403 when the feature is absent.
 *
 * ── WHY THIS IS AN ANNOTATION, NOT ADMIN CONFIG ─────────────────────────────
 * The mapping "endpoint -> required feature" is a SECURITY BOUNDARY. It lives in
 * code, next to the endpoint it protects, so it cannot be altered from a CRUD
 * screen (which would make that screen an authorization-bypass surface). What
 * IS admin-managed is the tenant's grant of the feature (feature_flags) and the
 * frontend nav/route gating (ui_navigation). This annotation is the server-side
 * lock those data layers cannot themselves provide.
 *
 * Usage:
 *   @RequiresFeature("module.vendor_assessment")
 *   @GetMapping ...                         // one endpoint
 *
 *   @RequiresFeature("framework.iso27001")
 *   @RestController ...                      // whole controller
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresFeature {

    /** The feature key that must be enabled for the current tenant. */
    String value();
}