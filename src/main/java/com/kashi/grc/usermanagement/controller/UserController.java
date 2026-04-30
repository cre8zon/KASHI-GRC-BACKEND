package com.kashi.grc.usermanagement.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.util.Constants;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.UserStatus;
import com.kashi.grc.usermanagement.dto.request.*;
import com.kashi.grc.usermanagement.dto.response.UserAccessSummary;
import com.kashi.grc.usermanagement.dto.response.UserResponse;
import com.kashi.grc.usermanagement.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Map;

/**
 * User management endpoints.
 /**
 * User management endpoints.
 * Tenant is resolved from the JWT — never passed as a request parameter.
 * POST   /v1/users                          Create user
 * POST   /v1/users/bulk-upload              Bulk CSV import
 * GET    /v1/users/{userId}                 Get user by ID
 * GET    /v1/users                          List (paginated + search + filter + sort)
 * PUT    /v1/users/{userId}                 Update user
 * DELETE /v1/users/{userId}                 Soft-delete
 * PATCH  /v1/users/{userId}/suspend         Suspend-user
 * PATCH  /v1/users/{userId}/activate        Activate-user
 * PATCH  /v1/users/{userId}/status          Change status
 * GET    /v1/users/{userId}/responsibilities Pre-deactivation check
 * POST   /v1/users/{userId}/deactivate      Deactivate-user
 * POST   /v1/users/{userId}/reactivate      Reactivate-user
 * PUT    /v1/users/password                 Change own password
 * GET    /v1/users/{userId}/access-summary  Full access profile
 * GET    /v1/users/{userId}/activity-log    Access-history
 * All endpoints require: Authorization: Bearer <token>
 *                        X-Tenant-ID: <tenantId>
 */
@Slf4j
@RestController
@RequestMapping("/v1/users")
@Tag(name = "User Management", description = "CRUD and lifecycle management for users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UtilityService utilityService;

    // ── CREATE ────────────────────────────────────────────────────
    @PostMapping
    @Operation(summary = "Provision a new user")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody UserCreateRequest request) {
        UserResponse user = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(user));
    }

    // ── BULK USER UPLOAD ─────────────────────────────────────────────
    @PostMapping(value = "/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk create users from CSV")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long defaultRoleId,
            @RequestParam(defaultValue = "true") boolean sendWelcomeEmails) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.bulkUpload(file, defaultRoleId, sendWelcomeEmails)));
    }

    // ── GET BY ID ─────────────────────────────────────────────────
    @GetMapping("/{userId}")
    @Operation(summary = "Get user details by ID")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(
            @PathVariable Long userId) {
        UserResponse user = userService.getUserById(userId);
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    // ── LIST (paginated + filtered) ───────────────────────────────
    /**
     * List users with pagination, search, filter, and sort.
     * Query params:
     *   skip=0  take=20
     *   search=firstname=john;email=@acme
     *   filterBy=status=ACTIVE;department=IT
     *   sortBy=email  sortDirection=asc
     */
    @GetMapping
    @Operation(summary = "List users — paginated, filterable, searchable, sortable")
    public ResponseEntity<ApiResponse<PaginatedResponse<UserResponse>>> listUsers(
            @RequestParam Map<String, String> allParams) {
        String side    = allParams.get("side");  // ← extract before passing to pageDetails
        boolean noRoles = "true".equalsIgnoreCase(allParams.get("noRoles"));
        // vendorId filter: org-admin drilling into a specific vendor's team,
        // OR scoping override. Vendor-side users are always scoped to their own
        // vendor by the service (via loggedInUser.getVendorId()) — this param
        // is only honoured for org/system callers.
        Long vendorId = null;
        String vendorIdStr = allParams.get("vendorId");
        if (vendorIdStr != null && !vendorIdStr.isBlank()) {
            try { vendorId = Long.parseLong(vendorIdStr); } catch (NumberFormatException ignored) {}
        }
        return ResponseEntity.ok(ApiResponse.success(
                userService.listUsers(utilityService.getpageDetails(allParams), side, noRoles, vendorId)));
    }

    // ── UPDATE ────────────────────────────────────────────────────
    @PutMapping("/{userId}")
    @Operation(summary = "Update user profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UserUpdateRequest request) {
        UserResponse user = userService.updateUser(userId, request);
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    // ── DELETE ────────────────────────────────────────────────────
    @DeleteMapping("/{userId}")
    @Operation(summary = "Soft-delete a user")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable Long userId) {
        userService.deleteUser(userId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── SUSPEND ───────────────────────────────────────────────────
    @PatchMapping("/{userId}/suspend")
    @Operation(summary = "Suspend a user account")
    public ResponseEntity<ApiResponse<UserResponse>> suspendUser(
            @PathVariable Long userId) {
        UserResponse user = userService.suspendUser(userId);
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    // ── ACTIVATE ──────────────────────────────────────────────────
    @PatchMapping("/{userId}/activate")
    @Operation(summary = "Activate or reactivate a user account")
    public ResponseEntity<ApiResponse<UserResponse>> activateUser(
            @PathVariable Long userId) {
        UserResponse user = userService.activateUser(userId);
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Change user lifecycle status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStatus(
            @PathVariable Long userId,
            @Valid @RequestBody UserStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.updateStatus(userId, request)));
    }

    @GetMapping("/{userId}/responsibilities")
    @Operation(summary = "Get active assignments before deactivation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getResponsibilities(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "true") boolean includeDelegations) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.getResponsibilities(userId, includeDelegations)));
    }

    @PostMapping("/{userId}/deactivate")
    @Operation(summary = "Deactivate user with responsibility reassignment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UserDeactivateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.deactivateUser(userId, request)));
    }

    @PostMapping("/{userId}/reactivate")
    @Operation(summary = "Restore deactivated user")
    public ResponseEntity<ApiResponse<UserResponse>> reactivateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UserReactivateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.reactivateUser(userId, request)));
    }

    @PutMapping("/password")
    @Operation(summary = "Change own password (authenticated user)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.changePassword(request)));
    }

    @GetMapping("/{userId}/access-summary")
    @Operation(summary = "Complete access profile for user")
    public ResponseEntity<ApiResponse<UserAccessSummary>> getAccessSummary(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(userService.getAccessSummary(userId)));
    }

    @GetMapping("/{userId}/activity-log")
    @Operation(summary = "Retrieve user access history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActivityLog(
            @PathVariable Long userId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String actionType,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.getActivityLog(userId, startDate, endDate, actionType, limit)));
    }

    /** Save per-user UI preferences (app_theme, sidebar_theme) to user_attributes */
    @PatchMapping("/me/preferences")
    public ResponseEntity<ApiResponse<Void>> savePreferences(
            @RequestBody Map<String, String> prefs) {
        userService.savePreferences(prefs);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Get per-user UI preferences from user_attributes */
    @GetMapping("/me/preferences")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPreferences() {
        return ResponseEntity.ok(ApiResponse.success(userService.getPreferences()));
    }
}