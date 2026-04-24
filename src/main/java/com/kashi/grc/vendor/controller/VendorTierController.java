package com.kashi.grc.vendor.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.vendor.domain.VendorTier;
import com.kashi.grc.vendor.dto.request.VendorTierRequest;
import com.kashi.grc.vendor.dto.response.VendorTierResponse;
import com.kashi.grc.vendor.repository.VendorTierRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/vendor-tiers")
@Tag(name = "Vendor Tier Management", description = "Vendor classification tier configuration")
@RequiredArgsConstructor
public class VendorTierController {

    private final VendorTierRepository vendorTierRepository;
    private final DbRepository         dbRepository;
    private final UtilityService       utilityService;

    @PostMapping
    @Operation(summary = "Create a vendor tier")
    public ResponseEntity<ApiResponse<VendorTierResponse>> createTier(
            @Valid @RequestBody VendorTierRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        VendorTier tier = VendorTier.builder()
                .tenantId(tenantId).tierName(req.getTierName())
                .assessmentFrequencyMonths(req.getAssessmentFrequencyMonths())
                .requiresSoc2(Boolean.TRUE.equals(req.getRequiresSoc2()))
                .requiresIso27001(Boolean.TRUE.equals(req.getRequiresIso27001()))
                .description(req.getDescription())
                .build();
        vendorTierRepository.save(tier);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(tier)));
    }

    @GetMapping
    @Operation(summary = "List vendor tiers — paginated, filterable, sortable")
    public ResponseEntity<ApiResponse<PaginatedResponse<VendorTierResponse>>> listTiers(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                VendorTier.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(cb.equal(root.get("tenantId"), tenantId)),
                (cb, root) -> Map.of("tiername", root.get("tierName"), "description", root.get("description")),
                this::toResponse
        )));
    }

    @GetMapping("/{tierId}")
    @Operation(summary = "Get vendor tier by ID")
    public ResponseEntity<ApiResponse<VendorTierResponse>> getTier(@PathVariable Long tierId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        VendorTier tier = vendorTierRepository.findByIdAndTenantId(tierId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorTier", tierId));
        return ResponseEntity.ok(ApiResponse.success(toResponse(tier)));
    }

    @PutMapping("/{tierId}")
    @Operation(summary = "Update vendor tier")
    public ResponseEntity<ApiResponse<VendorTierResponse>> updateTier(
            @PathVariable Long tierId, @RequestBody VendorTierRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        VendorTier tier = vendorTierRepository.findByIdAndTenantId(tierId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorTier", tierId));
        if (req.getTierName()                   != null) tier.setTierName(req.getTierName());
        if (req.getAssessmentFrequencyMonths()  != null) tier.setAssessmentFrequencyMonths(req.getAssessmentFrequencyMonths());
        if (req.getRequiresSoc2()               != null) tier.setRequiresSoc2(req.getRequiresSoc2());
        if (req.getRequiresIso27001()           != null) tier.setRequiresIso27001(req.getRequiresIso27001());
        if (req.getDescription()                != null) tier.setDescription(req.getDescription());
        vendorTierRepository.save(tier);
        return ResponseEntity.ok(ApiResponse.success(toResponse(tier)));
    }

    private VendorTierResponse toResponse(VendorTier t) {
        return VendorTierResponse.builder()
                .tierId(t.getId()).tierName(t.getTierName())
                .assessmentFrequencyMonths(t.getAssessmentFrequencyMonths())
                .requiresSoc2(t.isRequiresSoc2()).requiresIso27001(t.isRequiresIso27001())
                .description(t.getDescription()).createdAt(t.getCreatedAt())
                .build();
    }
}
