package com.kashi.grc.ucf.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.ucf.dto.CommonControlDtos.*;
import com.kashi.grc.ucf.service.CommonControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Common control catalogue — the framework-agnostic layer that KashiLink
 * matches against and that the crosswalk hangs off.
 *
 *   GET    /v1/ucf/controls/tree        DOMAIN -> FAMILY -> CONTROL, with usage
 *   GET    /v1/ucf/controls/picker      leaf options for the library tag picker
 *   GET    /v1/ucf/controls/{code}      detail: ancestry, mappings, library usage
 *   POST   /v1/ucf/controls             create a node
 *   PUT    /v1/ucf/controls/{code}      edit title/description/legacy tag/order
 *   DELETE /v1/ucf/controls/{code}      deactivate (blocked while in use)
 *
 *   GET    /v1/ucf/controls/{code}/expanded   ancestry chain as a CSV tag set
 *   GET    /v1/ucf/mappings/framework/{ref}   reverse view for one framework
 *   POST   /v1/ucf/mappings                   add a crosswalk row
 *   PUT    /v1/ucf/mappings/{id}              correct a relationship
 *   DELETE /v1/ucf/mappings/{id}              deactivate a crosswalk row
 *   GET    /v1/ucf/coverage                   citations covered per framework
 *
 * Reads are open to any authenticated user — the picker is needed by anyone
 * authoring a control. Writes are restricted: the catalogue is shared reference
 * data, and an accidental edit propagates to every tenant.
 */
@Slf4j
@RestController
@RequestMapping("/v1/ucf")
@Tag(name = "UCF Catalogue", description = "Common controls and framework crosswalk")
@RequiredArgsConstructor
public class CommonControlController {

    private final CommonControlService service;
    private final com.kashi.grc.common.util.UtilityService utilityService;


    // ══════════════════════════════════════════════════════════════════════
    // THE UCF CATALOGUE IS PLATFORM DATA
    //
    // common_controls and their framework mappings are the crosswalk that makes
    // one piece of evidence satisfy SOC 2, ISO 27001 and RBI at once. They are
    // global by definition — there is no tenant column to check, which is
    // exactly why every write here needs an explicit system-user gate rather
    // than an ownership comparison. Without it any org admin could rewrite or
    // deactivate a common control and change cross-framework mapping for every
    // tenant on the instance.
    //
    // Reads stay open: the catalogue is meant to be browsed, and the tag picker
    // on the control form calls it on every keystroke.
    // ══════════════════════════════════════════════════════════════════════

    private void requirePlatformAdmin(String action) {
        if (utilityService.isSystemUser()) return;

        var ctx = utilityService.getLoggedInDataContext();
        log.warn("[UCF] Refused {} by non-platform user | userId={} tenantId={}",
                action, ctx.getId(), ctx.getTenantId());
        throw new com.kashi.grc.common.exception.BusinessException("UCF_ADMIN_ONLY",
                "The common control catalogue is maintained by the platform",
                org.springframework.http.HttpStatus.FORBIDDEN);
    }

    // ── Catalogue ───────────────────────────────────────────────────────────

    @GetMapping("/controls/tree")
    @Operation(summary = "Full catalogue as a three-level tree with library usage counts")
    public ResponseEntity<ApiResponse<List<NodeResponse>>> tree() {
        return ResponseEntity.ok(ApiResponse.success(service.tree()));
    }

    /**
     * Tag picker for the audit library control form.
     *
     * Replaces free-text control_tag entry. Matches on code, title AND legacy
     * tag, so someone typing the old vocabulary ('MFA_ADMIN') still lands on the
     * right entry (IAM-02.3). Called on keystroke, so keep it lean.
     */
    @GetMapping("/controls/picker")
    @Operation(summary = "Leaf-level options for the control tag picker")
    public ResponseEntity<ApiResponse<List<PickerOption>>> picker(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(service.picker(q, domain, limit)));
    }

    @GetMapping("/controls/{code}")
    @Operation(summary = "One common control with ancestry, crosswalk and library usage")
    public ResponseEntity<ApiResponse<DetailResponse>> detail(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.success(service.detail(code)));
    }

    /**
     * The ancestry chain as a comma-separated tag set — 'IAM-02.3,IAM-02,IAM'.
     * This is exactly what Phase 3 will freeze into matched_tags_snapshot at
     * instantiation. Exposed now so the expansion can be verified against real
     * data before it is wired into AuditSectionService.
     */
    @GetMapping("/controls/{code}/expanded")
    @Operation(summary = "Ancestry chain as a CSV tag set (Phase 3 preview)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> expanded(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "code", code,
                "matchedTagSnapshot", String.valueOf(service.expandedTagSet(code)))));
    }

    @PostMapping("/controls")
    @Operation(summary = "Create a catalogue node")
    public ResponseEntity<ApiResponse<NodeResponse>> create(@Valid @RequestBody NodeRequest req) {
        requirePlatformAdmin("create");
        return ResponseEntity.ok(ApiResponse.success(service.createNode(req)));
    }

    @PutMapping("/controls/{code}")
    @Operation(summary = "Update a catalogue node",
            description = "code, parentCode and nodeLevel are immutable — library rows and "
                    + "frozen engagement snapshots already reference them.")
    public ResponseEntity<ApiResponse<NodeResponse>> update(
            @PathVariable String code, @Valid @RequestBody NodeRequest req) {
        requirePlatformAdmin("update");
        return ResponseEntity.ok(ApiResponse.success(service.updateNode(code, req)));
    }

    @DeleteMapping("/controls/{code}")
    @Operation(summary = "Deactivate a catalogue node",
            description = "Never a hard delete — the code may already be frozen into an "
                    + "engagement's matched_tags_snapshot. Blocked while library rows use it.")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable String code) {
        requirePlatformAdmin("deactivate");
        service.deactivateNode(code);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Crosswalk ───────────────────────────────────────────────────────────

    @PostMapping("/mappings")
    @Operation(summary = "Add a crosswalk row")
    public ResponseEntity<ApiResponse<MappingResponse>> createMapping(
            @Valid @RequestBody MappingRequest req) {
        requirePlatformAdmin("createMapping");
        return ResponseEntity.ok(ApiResponse.success(service.createMapping(req)));
    }

    /**
     * Correcting a DERIVED row flips its source to KASHI — that is how the
     * review backlog empties. Rows generated from the library default to
     * INTERSECTS_WITH because scope cannot be derived; a human setting the real
     * relationship is the only manual step the crosswalk genuinely needs.
     */
    @PutMapping("/mappings/{id}")
    @Operation(summary = "Correct a crosswalk relationship")
    public ResponseEntity<ApiResponse<MappingResponse>> updateMapping(
            @PathVariable Long id, @Valid @RequestBody MappingRequest req) {
        requirePlatformAdmin("updateMapping");
        return ResponseEntity.ok(ApiResponse.success(service.updateMapping(id, req)));
    }

    @DeleteMapping("/mappings/{id}")
    @Operation(summary = "Deactivate a crosswalk row")
    public ResponseEntity<ApiResponse<Void>> deleteMapping(@PathVariable Long id) {
        requirePlatformAdmin("deleteMapping");
        service.deleteMapping(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/coverage")
    @Operation(summary = "Citations covered per framework")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> coverage() {
        return ResponseEntity.ok(ApiResponse.success(service.frameworkCoverage()));
    }
}