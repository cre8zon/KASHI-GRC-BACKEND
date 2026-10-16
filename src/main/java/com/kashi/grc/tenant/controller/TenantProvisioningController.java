package com.kashi.grc.tenant.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.tenant.dto.request.TenantProvisionRequest;
import com.kashi.grc.tenant.service.TenantProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Create Tenant wizard endpoints. Under /v1/tenants/** — SIDE_SYSTEM only,
 * enforced by SecurityConfig.
 *
 *   GET  /v1/tenants/availability   name / code / admin email checks for one step
 *   POST /v1/tenants/provision      tenant + admin + subscription + features, atomically
 *
 * POST /v1/tenants (TenantController.createTenant) is left exactly as it was.
 */
@RestController
@RequestMapping("/v1/tenants")
@Tag(name = "Tenant Management — provisioning")
@RequiredArgsConstructor
public class TenantProvisioningController {

    private final TenantProvisioningService provisioningService;
    private final UtilityService            utilityService;

    @GetMapping("/availability")
    @Operation(summary = "Check a tenant name, code and admin email before submitting")
    public ApiResponse<Map<String, Object>> availability(@RequestParam(required = false) String name,
                                                         @RequestParam(required = false) String code,
                                                         @RequestParam(required = false) String adminEmail) {
        return ApiResponse.success(provisioningService.availability(name, code, adminEmail));
    }

    @PostMapping("/provision")
    @Operation(summary = "Create a tenant, its first administrator and its first subscription in one transaction")
    public ResponseEntity<ApiResponse<Map<String, Object>>> provision(@RequestBody TenantProvisionRequest req) {
        Long actor = utilityService.getLoggedInDataContext().getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(provisioningService.provision(req, actor)));
    }
}