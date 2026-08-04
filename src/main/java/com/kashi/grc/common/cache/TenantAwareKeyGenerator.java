package com.kashi.grc.common.cache;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Registered as the DEFAULT key generator for every {@code @Cacheable} /
 * {@code @CachePut} in the app (wired via CacheConfig#keyGenerator()).
 *
 * ── WHY THIS EXISTS ──────────────────────────────────────────────────────
 * The single most dangerous failure mode for a cache in a multi-tenant app
 * is a developer adding {@code @Cacheable("someCache")} on a method and
 * forgetting that the method's arguments alone (e.g. a formKey like
 * "issue_create_form") are IDENTICAL across every tenant. Without tenant
 * scoping baked into the key, tenant A's first request populates a cache
 * entry that tenant B's identical request then reads — a cross-tenant data
 * leak indistinguishable from a real bug report until it's a security
 * incident.
 *
 * Rather than requiring every call site to remember
 * {@code key = "#formKey + ':' + T(...).getCurrentTenant()"} (easy to
 * forget, easy to get wrong), this key generator makes tenant-scoping the
 * DEFAULT behavior for the whole app: every {@code @Cacheable} that does
 * not explicitly set {@code key} or {@code keyGenerator} gets
 * {@code "<tenantId-or-global>:<methodName>:<arg1>:<arg2>..."} automatically.
 *
 * A handful of caches ARE legitimately tenant-independent (e.g. the global
 * UCF catalogue) — those opt out by using their own explicit key, not by
 * this generator's default. TenantContext.getCurrentTenant() returns null
 * on scheduler/consumer threads (no TenantContext there), which is handled
 * by falling back to the literal string "global" rather than caching under
 * a null-keyed bucket that would be shared across every no-tenant caller.
 */
@Component("tenantAwareKeyGenerator")
public class TenantAwareKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        Long tenantId = TenantContext.getCurrentTenant();
        StringBuilder key = new StringBuilder(64);
        key.append(tenantId != null ? tenantId : "global").append(':');
        key.append(method.getName());
        for (Object p : params) {
            key.append(':').append(p == null ? "null" : p.toString());
        }
        return key.toString();
    }
}