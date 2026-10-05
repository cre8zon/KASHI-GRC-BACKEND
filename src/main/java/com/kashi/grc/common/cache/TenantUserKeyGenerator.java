package com.kashi.grc.common.cache;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Cache key for results that depend on WHO is asking, not just the tenant:
 * {@code "<tenant>:<user>:<method>:<args>"}.
 *
 * TenantAwareKeyGenerator (the default) is right for shared reference data, but
 * the UI config methods filter by the caller's sides and permissions — the
 * screen's actions, the dashboard widgets. Keyed by tenant + screen only, the
 * first user to open a screen decided the buttons everyone else in the tenant
 * saw for the next five minutes: an auditee got the org owner's "Raise finding"
 * and "Not tested", and lost their own "Submit for review".
 *
 * The principal name is the user id (see SecurityContextHelper.userId). No
 * authenticated user → "anon", never a bucket shared with a real user.
 */
@Component("tenantUserKeyGenerator")
public class TenantUserKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        Long tenantId = TenantContext.getCurrentTenant();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null && auth.isAuthenticated() && auth.getName() != null ? auth.getName() : "anon";
        StringBuilder key = new StringBuilder(80);
        key.append(tenantId != null ? tenantId : "global").append(':')
           .append('u').append(user).append(':')
           .append(method.getName());
        for (Object p : params) {
            key.append(':').append(p == null ? "null" : p.toString());
        }
        return key.toString();
    }
}
