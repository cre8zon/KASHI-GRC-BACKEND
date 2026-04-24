package com.kashi.grc.uiconfig.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.uiconfig.domain.UiState;
import com.kashi.grc.uiconfig.dto.request.UiStateRequest;
import com.kashi.grc.uiconfig.dto.response.UiStateResponse;
import com.kashi.grc.uiconfig.repository.UiStateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@Tag(name = "UI State Management", description = "DB-driven success / error / empty state definitions per screen")
@RequiredArgsConstructor
public class UiStateController {

    private final UiStateRepository uiStateRepository;
    private final DbRepository      dbRepository;
    private final UtilityService    utilityService;

    // ── Frontend read: get all states for a screen ─────────────────
    @GetMapping("/v1/ui-config/states/{screenKey}")
    @Operation(summary = "Get all state definitions (success/error/empty) for a screen")
    public ResponseEntity<ApiResponse<Map<String, UiStateResponse>>> getStatesForScreen(
            @PathVariable String screenKey) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<UiState> states = uiStateRepository.findByScreenForTenant(screenKey, tenantId);

        // Return as map: stateType -> UiStateResponse
        // Tenant-specific row overrides global (list is ordered tenant NULLS LAST,
        // so we collect and let later tenant entry win via toMap merge)
        Map<String, UiStateResponse> result = states.stream()
                .collect(Collectors.toMap(
                        UiState::getStateType,
                        this::toResponse,
                        (global, tenant) -> tenant   // tenant row wins
                ));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── Admin CRUD ─────────────────────────────────────────────────
    @PostMapping("/v1/admin/ui/states")
    @Operation(summary = "Create a UI state definition")
    public ResponseEntity<ApiResponse<UiStateResponse>> create(
            @Valid @RequestBody UiStateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiState s = UiState.builder()
                .screenKey(req.getScreenKey()).stateType(req.getStateType())
                .title(req.getTitle()).description(req.getDescription())
                .icon(req.getIcon()).colorTag(req.getColorTag())
                .ctaLabel(req.getCtaLabel()).ctaAction(req.getCtaAction())
                .secondaryCtaLabel(req.getSecondaryCtaLabel())
                .secondaryCtaAction(req.getSecondaryCtaAction())
                .isActive(req.isActive()).tenantId(tenantId)
                .build();
        uiStateRepository.save(s);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toResponse(s)));
    }

    @GetMapping("/v1/admin/ui/states")
    @Operation(summary = "List all UI state definitions — paginated")
    public ResponseEntity<ApiResponse<PaginatedResponse<UiStateResponse>>> list(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                UiState.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(cb.or(
                        cb.isNull(root.get("tenantId")),
                        cb.equal(root.get("tenantId"), tenantId))),
                (cb, root) -> Map.of(
                        "screenkey", root.get("screenKey"),
                        "statetype", root.get("stateType"),
                        "title",     root.get("title")),
                this::toResponse)));
    }

    @PutMapping("/v1/admin/ui/states/{id}")
    @Operation(summary = "Update a UI state definition")
    public ResponseEntity<ApiResponse<UiStateResponse>> update(
            @PathVariable Long id, @RequestBody UiStateRequest req) {
        UiState s = uiStateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiState", id));
        if (req.getTitle()               != null) s.setTitle(req.getTitle());
        if (req.getDescription()         != null) s.setDescription(req.getDescription());
        if (req.getIcon()                != null) s.setIcon(req.getIcon());
        if (req.getColorTag()            != null) s.setColorTag(req.getColorTag());
        if (req.getCtaLabel()            != null) s.setCtaLabel(req.getCtaLabel());
        if (req.getCtaAction()           != null) s.setCtaAction(req.getCtaAction());
        if (req.getSecondaryCtaLabel()   != null) s.setSecondaryCtaLabel(req.getSecondaryCtaLabel());
        if (req.getSecondaryCtaAction()  != null) s.setSecondaryCtaAction(req.getSecondaryCtaAction());
        s.setActive(req.isActive());
        uiStateRepository.save(s);
        return ResponseEntity.ok(ApiResponse.success(toResponse(s)));
    }

    @DeleteMapping("/v1/admin/ui/states/{id}")
    @Operation(summary = "Delete a UI state definition")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        uiStateRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    private UiStateResponse toResponse(UiState s) {
        return UiStateResponse.builder()
                .id(s.getId()).screenKey(s.getScreenKey()).stateType(s.getStateType())
                .title(s.getTitle()).description(s.getDescription()).icon(s.getIcon())
                .colorTag(s.getColorTag()).ctaLabel(s.getCtaLabel()).ctaAction(s.getCtaAction())
                .secondaryCtaLabel(s.getSecondaryCtaLabel())
                .secondaryCtaAction(s.getSecondaryCtaAction())
                .build();
    }
}
