package com.kashi.grc.common.util;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.dto.LoggedInDetails;
import com.kashi.grc.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Central helper that pulls the authenticated user's ID and tenant ID
 * from the Spring Security context.
 *
 * The JWT filter already sets TenantContext and stores the user ID as
 * the principal name, so we never ask the frontend for tenant_id again.
 *
 * Usage (inject into any service/controller):
 * <pre>
 *   Long tenantId = security.tenantId();
 *   Long userId   = security.userId();
 *   LoggedInDetails me = security.current();
 * </pre>
 */
@Component
public class SecurityContextHelper {

    /** Returns the tenant ID from TenantContext (populated by JwtAuthenticationFilter). */
    public Long tenantId() {
        Long id = TenantContext.getCurrentTenant();
        if (id == null) {
            throw new BusinessException("AUTH_MISSING_TENANT",
                    "No tenant in security context — is the request authenticated?", HttpStatus.UNAUTHORIZED);
        }
        return id;
    }

    /** Returns the authenticated user's ID (stored as the principal name). */
    public Long userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new BusinessException("AUTH_NOT_AUTHENTICATED",
                    "No authenticated user in context", HttpStatus.UNAUTHORIZED);
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) {
            return Long.parseLong(ud.getUsername());
        }
        if (principal instanceof String s) {
            return Long.parseLong(s);
        }
        throw new BusinessException("AUTH_PRINCIPAL_TYPE",
                "Unexpected principal type: " + principal.getClass(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** Convenience — returns both values together. */
    public LoggedInDetails current() {
        return new LoggedInDetails(userId(), tenantId());
    }
}
