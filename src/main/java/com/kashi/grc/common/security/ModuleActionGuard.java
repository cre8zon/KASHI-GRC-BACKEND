package com.kashi.grc.common.security;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.uiconfig.service.UiConfigService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.workflow.dto.response.AccessContext;
import com.kashi.grc.workflow.service.WorkflowAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Enforces on the server the permission that already hides a module's buttons.
 *
 * ── WHY THIS EXISTS ────────────────────────────────────────────────────────
 * The risk, issue, incident, asset, personnel and exception controllers check
 * tenancy, but not permissions. Each lifecycle button (accept risk, close
 * issue, approve exception …) is a ui_actions row whose required_permission and
 * allowed_sides decide whether the button is SHOWN — and that was the only
 * check. Vendor users share the organisation's tenant, so anyone holding a
 * token could POST /v1/risks/{id}/accept directly and the tenant check passed.
 *
 * ── WHAT IT DOES ───────────────────────────────────────────────────────────
 * For a POST, PUT, PATCH or DELETE under one of the guarded module paths, it
 * looks up the active ui_actions bound to that endpoint. If there are any, the
 * caller must be one who would be shown at least one of them — the same rule
 * UiConfigServiceImpl.getActions uses to serve the buttons (permission codes
 * including permission_grants, and sides). Otherwise: 403.
 *
 * So the server now refuses exactly what the screen already hides. Nothing a
 * user can do through the UI today is refused, and a role or permission change
 * made in the admin screens applies to the API with no code change — there is
 * no permission code written in here.
 *
 * Endpoints with no ui_actions row (create, edit forms, form-submitted
 * lifecycle moves, linking) are checked against ModuleWriteRules instead: the
 * caller needs one of the listed permission codes, or an open task / override
 * authority on the record's active workflow step.
 * Platform operators (SIDE_SYSTEM or system:write) pass, as in PermissionGate.
 *
 * ── SWITCHES ───────────────────────────────────────────────────────────────
 *   kashi.security.module-action-guard.mode   enforce (default) | report | off
 *       for button-bound endpoints. These match what the screen already
 *       hides, so enforcing them cannot refuse anything the UI offers.
 *   kashi.security.module-write-guard.mode    report (default) | enforce | off
 *       for ModuleWriteRules. Starts in report because which roles hold which
 *       codes is per-tenant data: report logs every request it WOULD refuse
 *       and lets it through. Grant the missing codes, then switch to enforce.
 *   kashi.security.module-action-guard.paths  comma-separated path prefixes
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModuleActionGuard implements HandlerInterceptor {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final UiConfigService       uiConfigService;
    private final PermissionGate        permissionGate;
    private final UtilityService        utilityService;
    private final WorkflowAccessService workflowAccessService;

    @Value("${kashi.security.module-action-guard.mode:enforce}")
    private String mode;

    @Value("${kashi.security.module-write-guard.mode:report}")
    private String writeMode;

    @Value("${kashi.security.module-action-guard.paths:"
            + "/v1/risks,/v1/issues,/v1/incidents,/v1/assets,/v1/personnel,/v1/exceptions}")
    private String paths;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("off".equalsIgnoreCase(mode) && "off".equalsIgnoreCase(writeMode)) return true;
        if (!MUTATING.contains(request.getMethod().toUpperCase())) return true;

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!guarded(path)) return true;

        // Anonymous routes (e.g. POST /v1/issues/ingest) were already decided by
        // SecurityConfig; there is no caller to check here.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return true;

        if (permissionGate.isPlatform()) return true;

        String method = request.getMethod().toUpperCase();

        // 1. Button-bound endpoint: the button's own permission and sides decide.
        Boolean allowed = "off".equalsIgnoreCase(mode) ? null
                : uiConfigService.callerMayInvokeActionEndpoint(method, path);
        if (allowed != null) {
            if (allowed) return true;
            return refuse(mode, "action", method, path, auth, null);
        }

        // 2. Otherwise the endpoint's entry in ModuleWriteRules, if it has one.
        if ("off".equalsIgnoreCase(writeMode)) return true;
        ModuleWriteRules.Rule rule = ModuleWriteRules.match(method, path);
        if (rule == null) return true;

        User user = utilityService.getLoggedInUserWithRolesAndPermissions();
        List<String> perms = workflowAccessService.resolvePermissions(user);
        if (rule.codes().stream().anyMatch(perms::contains)) return true;
        if (onActiveStep(user, rule, path)) return true;

        return refuse(writeMode, "write", method, path, auth, rule.codes());
    }

    /**
     * The people the screen lets edit on a workflow step without the module
     * permission: the task holder, an override holder, or someone the step's
     * UI override grants the code to.
     */
    private boolean onActiveStep(User user, ModuleWriteRules.Rule rule, String path) {
        if (rule.entityType() == null) return false;
        Long id = ModuleWriteRules.entityId(rule, path);
        if (id == null) return false;
        try {
            AccessContext ac = workflowAccessService.resolveForModule(user, rule.entityType(), id);
            if (ac == null) return false;
            if (ac.isCanAct() || ac.isCanOverride()) return true;
            return ac.getPermissions() != null && rule.codes().stream().anyMatch(ac.getPermissions()::contains);
        } catch (RuntimeException e) {
            // A record with no workflow, or one this resolver cannot place:
            // the permission alone decides.
            log.debug("[MODULE-ACTION-GUARD] No step context for {} {} — {}", rule.entityType(), id, e.toString());
            return false;
        }
    }

    private boolean refuse(String activeMode, String kind, String method, String path,
                           Authentication auth, List<String> needed) {
        boolean report = "report".equalsIgnoreCase(activeMode);
        log.warn("[MODULE-ACTION-GUARD] {} | {} | {} {} | needed one of {} | principal={} | held={}",
                report ? "Would refuse (report mode)" : "Refused", kind,
                method, path, needed == null ? "the button's permission" : needed,
                auth.getName(), auth.getAuthorities());
        if (report) return true;

        throw new BusinessException(
                "PERMISSION_DENIED",
                "You do not have permission to perform this action.",
                HttpStatus.FORBIDDEN);
    }

    private boolean guarded(String path) {
        List<String> prefixes = Arrays.stream(paths.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
        return prefixes.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }
}