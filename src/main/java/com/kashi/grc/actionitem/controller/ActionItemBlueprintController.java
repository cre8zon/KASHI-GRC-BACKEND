package com.kashi.grc.actionitem.controller;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.domain.ActionItemBlueprint;
import com.kashi.grc.actionitem.repository.ActionItemBlueprintRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@Tag(name = "Action Item Blueprints", description = "Manage finding template library")
@RequiredArgsConstructor
public class ActionItemBlueprintController {

    private final ActionItemBlueprintRepository blueprintRepository;
    private final UtilityService                utilityService;

    @GetMapping("/v1/action-item-blueprints")
    @Operation(summary = "List blueprints visible to this tenant (global + own)")
    public ResponseEntity<ApiResponse<List<ActionItemBlueprint>>> list() {
        User user = utilityService.getLoggedInDataContext();
        List<ActionItemBlueprint> items = blueprintRepository.findVisibleToTenant(user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @PostMapping("/v1/action-item-blueprints")
    @Operation(summary = "Create a blueprint")
    public ResponseEntity<ApiResponse<ActionItemBlueprint>> create(
            @Valid @RequestBody BlueprintRequest req) {
        User user = utilityService.getLoggedInDataContext();

        // Only platform admins can create global blueprints
        if (req.getTenantId() == null && user.getTenantId() != null) {
            req.setTenantId(user.getTenantId()); // force tenant scope
        }

        // Unique code check
        if (req.getBlueprintCode() != null) {
            blueprintRepository.findByBlueprintCode(req.getBlueprintCode()).ifPresent(b -> {
                throw new BusinessException("DUPLICATE_CODE",
                        "Blueprint code '" + req.getBlueprintCode() + "' already exists");
            });
        }

        ActionItemBlueprint bp = ActionItemBlueprint.builder()
                .tenantId(req.getTenantId())
                .sourceType(req.getSourceType())
                .category(req.getCategory())
                .titleTemplate(req.getTitleTemplate())
                .descriptionTemplate(req.getDescriptionTemplate())
                .resolutionRole(req.getResolutionRole())
                .defaultPriority(req.getDefaultPriority() != null
                        ? req.getDefaultPriority() : ActionItem.Priority.MEDIUM)
                .standardRef(req.getStandardRef())
                .blueprintCode(req.getBlueprintCode())
                .isActive(true)
                .build();

        blueprintRepository.save(bp);
        log.info("[BLUEPRINT] Created id={} code={}", bp.getId(), bp.getBlueprintCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(bp));
    }

    @PutMapping("/v1/action-item-blueprints/{id}")
    @Operation(summary = "Update a blueprint")
    public ResponseEntity<ApiResponse<ActionItemBlueprint>> update(
            @PathVariable Long id,
            @Valid @RequestBody BlueprintRequest req) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemBlueprint bp = blueprintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ActionItemBlueprint", id));

        // Tenants cannot modify global blueprints
        if (bp.getTenantId() == null && user.getTenantId() != null) {
            throw new BusinessException("FORBIDDEN",
                    "Global blueprints can only be modified by platform administrators",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }

        bp.setSourceType(req.getSourceType());
        bp.setCategory(req.getCategory());
        bp.setTitleTemplate(req.getTitleTemplate());
        bp.setDescriptionTemplate(req.getDescriptionTemplate());
        bp.setResolutionRole(req.getResolutionRole());
        if (req.getDefaultPriority() != null) bp.setDefaultPriority(req.getDefaultPriority());
        bp.setStandardRef(req.getStandardRef());
        if (req.getIsActive() != null) bp.setIsActive(req.getIsActive());

        blueprintRepository.save(bp);
        return ResponseEntity.ok(ApiResponse.success(bp));
    }

    @DeleteMapping("/v1/action-item-blueprints/{id}")
    @Operation(summary = "Delete a blueprint")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemBlueprint bp = blueprintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ActionItemBlueprint", id));

        if (bp.getTenantId() == null && user.getTenantId() != null) {
            throw new BusinessException("FORBIDDEN",
                    "Global blueprints can only be deleted by platform administrators",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }

        blueprintRepository.delete(bp);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Data
    public static class BlueprintRequest {
        private Long                    tenantId;
        private ActionItem.SourceType   sourceType;
        private String                  category;
        private String                  titleTemplate;
        private String                  descriptionTemplate;
        private String                  resolutionRole;
        private ActionItem.Priority     defaultPriority;
        private String                  standardRef;
        private String                  blueprintCode;
        private Boolean                 isActive;
    }
}