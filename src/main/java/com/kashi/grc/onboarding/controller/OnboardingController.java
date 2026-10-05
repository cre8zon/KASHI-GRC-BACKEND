package com.kashi.grc.onboarding.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.onboarding.domain.PersonnelOnboardingItem;
import com.kashi.grc.onboarding.repository.PersonnelOnboardingItemRepository;
import com.kashi.grc.onboarding.service.OnboardingService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Onboarding checklists.
 *
 * The person's checklist rides the generic linked-* tab convention:
 * GET /v1/personnel/{id}/linked-onboarding is served here rather than by
 * PersonnelController, so the Personnel module needs no change to gain the tab.
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "Onboarding", description = "Joiner checklists and their progress")
public class OnboardingController {

    private final OnboardingService                 onboardingService;
    private final PersonnelOnboardingItemRepository itemRepository;
    private final UtilityService                    utilityService;

    /** The tab. Rows plus the progress summary the header shows. */
    @GetMapping("/personnel/{personnelId}/linked-onboarding")
    @Operation(summary = "This person's onboarding checklist")
    public ResponseEntity<ApiResponse<Map<String, Object>>> forPerson(@PathVariable Long personnelId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        List<Map<String, Object>> rows = itemRepository
                .findByPersonnelIdOrderBySortOrderAsc(personnelId).stream()
                .filter(i -> tenantId.equals(i.getTenantId()))
                .map(this::toRow)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("progress", onboardingService.progressFor(personnelId));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PostMapping("/onboarding/items/{id}/complete")
    @Operation(summary = "Mark an item done — refuses without evidence when the item requires it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> complete(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object doc = body == null ? null : body.get("evidenceDocumentId");
        Long docId = doc == null ? null : Long.parseLong(String.valueOf(doc));
        return ResponseEntity.ok(ApiResponse.success(toRow(
                onboardingService.complete(id, docId, ctx.getId(), ctx.getTenantId()))));
    }

    @PostMapping("/onboarding/items/{id}/waive")
    @Operation(summary = "Waive an item — a reason is required")
    public ResponseEntity<ApiResponse<Map<String, Object>>> waive(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(toRow(onboardingService.waive(
                id, r == null ? null : String.valueOf(r), ctx.getId(), ctx.getTenantId()))));
    }

    @PostMapping("/onboarding/items/{id}/not-applicable")
    @Operation(summary = "Mark an item not applicable to this person")
    public ResponseEntity<ApiResponse<Map<String, Object>>> notApplicable(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(toRow(
                onboardingService.markNotApplicable(id, ctx.getId(), ctx.getTenantId()))));
    }

    @PostMapping("/onboarding/items/{id}/reopen")
    @Operation(summary = "Reopen an item, clearing its evidence and reason")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reopen(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                toRow(onboardingService.reopen(id, tenantId))));
    }

    /**
     * A one-off item for one person.
     *
     * Onboarding always has exceptions — a specific clearance, a customer's own
     * background check. Forcing those into the shared template so they can be
     * tracked would impose them on everybody.
     */
    @PostMapping("/personnel/{personnelId}/onboarding-items")
    @Operation(summary = "Add a one-off checklist item for this person")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addAdHoc(
            @PathVariable Long personnelId, @RequestBody Map<String, Object> body) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(toRow(onboardingService.addAdHoc(
                personnelId,
                str(body.get("title")),
                str(body.get("category")),
                body.get("dueDays") == null ? null : Integer.parseInt(String.valueOf(body.get("dueDays"))),
                Boolean.TRUE.equals(body.get("requiresEvidence")),
                tenantId))));
    }

    @GetMapping("/onboarding/stats")
    @Operation(summary = "Tenant-level checklist counts for the dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(onboardingService.getStats(tenantId)));
    }

    private Map<String, Object> toRow(PersonnelOnboardingItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               i.getId());
        m.put("title",            i.getTitle());
        m.put("description",      i.getDescription());
        m.put("category",         i.getCategory());
        m.put("ownerRole",        i.getOwnerRole());
        m.put("status",           i.getStatus());
        m.put("isRequired",       i.isRequired());
        m.put("requiresEvidence", i.isRequiresEvidence());
        m.put("dueAt",            i.getDueAt());
        m.put("completedAt",      i.getCompletedAt());
        m.put("waiverReason",     i.getWaiverReason());
        m.put("evidenceDocumentId", i.getEvidenceDocumentId());
        // Computed, not stored — a row stored as overdue would stay overdue
        // after somebody moved the date.
        m.put("overdue",          i.isOverdue());
        m.put("editable",         i.getStatus() == PersonnelOnboardingItem.Status.PENDING);
        return m;
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}