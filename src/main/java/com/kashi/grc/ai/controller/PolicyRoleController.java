package com.kashi.grc.ai.controller;

import com.kashi.grc.ai.policy.PolicyRoleRegister;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.security.PermissionGate;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The policy role register, on the AI organisation profile screen.
 *
 * Reading is open to signed-in users of the tenant (policy readers see the
 * holders anyway, through {{role_<key>}}); changing it needs AUDIT_CREATE, the
 * same permission that saves the rest of the org profile.
 */
@RestController
@RequiredArgsConstructor
public class PolicyRoleController {

    private final PolicyRoleRegister register;
    private final UtilityService     utilityService;
    private final PermissionGate     permissionGate;

    @GetMapping("/v1/ai/org-profile/roles")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(register.list(utilityService.getLoggedInDataContext().getTenantId()));
    }

    @PostMapping("/v1/ai/org-profile/roles")
    public ApiResponse<Map<String, Object>> assign(@RequestBody AssignRequest req) {
        permissionGate.require("AUDIT_CREATE");
        User me = utilityService.getLoggedInDataContext();
        return ApiResponse.success(register.assign(me.getTenantId(), me.getId(),
                req.getRoleTitle(), req.getUserId(), req.getNote(), req.getJobTitle()));
    }

    @PostMapping("/v1/ai/org-profile/roles/{roleKey}/vacate")
    public ApiResponse<Void> vacate(@PathVariable String roleKey) {
        permissionGate.require("AUDIT_CREATE");
        register.vacate(utilityService.getLoggedInDataContext().getTenantId(), roleKey);
        return ApiResponse.success();
    }

    @Data
    public static class AssignRequest {
        private String roleTitle;
        private Long   userId;
        private String note;
        /** Optional. Saved as the holder's job title only if they have none. */
        private String jobTitle;
    }
}