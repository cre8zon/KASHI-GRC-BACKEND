package com.kashi.grc.notification.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.notification.domain.UserNotificationPreference;
import com.kashi.grc.notification.repository.UserNotificationPreferenceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Self-service notification preferences — /v1/me scope, no admin permission
 * needed: every user manages ONLY their own rows (userId always taken from
 * the JWT context, never from the request body).
 *
 * Frontend contract:
 *   GET    /v1/me/notification-preferences          → my saved rows
 *          (absence of a row for an eventKey = default: everything enabled;
 *           the UI renders the full event list and overlays these rows)
 *   PUT    /v1/me/notification-preferences          → upsert one row
 *          body { eventKey: "TASK_ASSIGNMENT" | "ALL", emailEnabled, inAppEnabled }
 *   DELETE /v1/me/notification-preferences/{eventKey} → reset to default
 */
@RestController
@RequestMapping("/v1/me/notification-preferences")
@Tag(name = "My Notification Preferences",
        description = "Per-user email/in-app opt-outs, per event or global (eventKey=ALL)")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final UserNotificationPreferenceRepository preferenceRepository;
    private final UtilityService utilityService;

    @GetMapping
    @Operation(summary = "List my saved preference rows (no row = default enabled)")
    public ResponseEntity<ApiResponse<List<UserNotificationPreference>>> myPreferences() {
        Long userId = utilityService.getLoggedInDataContext().getId();
        return ResponseEntity.ok(ApiResponse.success(preferenceRepository.findByUserId(userId)));
    }

    @PutMapping
    @Operation(summary = "Upsert one preference (eventKey or 'ALL' for my global default)")
    public ResponseEntity<ApiResponse<UserNotificationPreference>> upsert(
            @Valid @RequestBody PreferenceRequest req) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        UserNotificationPreference pref = preferenceRepository
                .findByUserIdAndEventKey(userId, req.getEventKey())
                .orElseGet(() -> UserNotificationPreference.builder()
                        .userId(userId)
                        .eventKey(req.getEventKey())
                        .build());
        if (req.getEmailEnabled() != null) pref.setEmailEnabled(req.getEmailEnabled());
        if (req.getInAppEnabled() != null) pref.setInAppEnabled(req.getInAppEnabled());
        pref.setUpdatedAt(LocalDateTime.now());
        preferenceRepository.save(pref);
        return ResponseEntity.ok(ApiResponse.success(pref));
    }

    @DeleteMapping("/{eventKey}")
    @Operation(summary = "Reset one eventKey (or 'ALL') back to default enabled")
    public ResponseEntity<ApiResponse<Void>> reset(@PathVariable String eventKey) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        preferenceRepository.findByUserIdAndEventKey(userId, eventKey)
                .ifPresent(preferenceRepository::delete);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Data
    public static class PreferenceRequest {
        @NotBlank private String eventKey;   // specific key or "ALL"
        private Boolean emailEnabled;
        private Boolean inAppEnabled;
    }
}
