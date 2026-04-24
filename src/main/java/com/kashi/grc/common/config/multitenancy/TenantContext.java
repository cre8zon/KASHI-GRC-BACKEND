package com.kashi.grc.common.config.multitenancy;

/**
 * Holds the current request's tenant ID in a ThreadLocal.
 * Populated by TenantInterceptor, read by repositories and services
 * to enforce complete tenant isolation.
 * IMPORTANT: Always call clear() after request processing (done in the interceptor).
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenant(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static Long getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
