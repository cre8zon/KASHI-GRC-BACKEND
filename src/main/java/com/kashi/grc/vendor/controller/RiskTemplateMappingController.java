package com.kashi.grc.vendor.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.vendor.domain.RiskTemplateMapping;
import com.kashi.grc.vendor.dto.request.RiskMappingConfigRequest;
import com.kashi.grc.vendor.dto.response.RiskMappingResponse;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.vendor.repository.VendorTierRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/v1/config/risk-template-mappings")
@Tag(name = "Risk-Template Mapping", description = "Configure risk score to assessment template mappings")
@RequiredArgsConstructor
public class RiskTemplateMappingController {

    private final RiskTemplateMappingRepository mappingRepository;
    private final VendorTierRepository          tierRepository;
    private final UtilityService                utilityService;

    @PostMapping
    @Transactional
    @Operation(summary = "Replace all global risk-to-template mappings (Platform Admin only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> configureMappings(
            @Valid @RequestBody RiskMappingConfigRequest req) {

        // Global mappings — owned by platform, tenant_id = NULL
        // Any org automatically uses these; no per-tenant override needed
        List<RiskMappingConfigRequest.MappingEntry> sorted = req.getMappings().stream()
                .sorted(Comparator.comparing(RiskMappingConfigRequest.MappingEntry::getMinScore))
                .toList();

        // Deduplicate to one entry per tier range before gap/overlap validation.
        // Multiple rows with the same (minScore, maxScore) are valid — they represent
        // multiple template options for the same tier. Validating all rows as if they
        // were distinct ranges produces false gap/overlap warnings.
        // One representative entry per distinct (minScore, maxScore) pair
        Set<String> seenRanges = new LinkedHashSet<>();
        List<RiskMappingConfigRequest.MappingEntry> uniqueRanges = new ArrayList<>();
        for (RiskMappingConfigRequest.MappingEntry e : sorted) {
            String rangeKey = e.getMinScore().stripTrailingZeros().toPlainString()
                    + "|" + e.getMaxScore().stripTrailingZeros().toPlainString();
            if (seenRanges.add(rangeKey)) {
                uniqueRanges.add(e);
            }
        }

        boolean noGaps = true, noOverlaps = true;
        for (int i = 1; i < uniqueRanges.size(); i++) {
            BigDecimal prevMax = uniqueRanges.get(i - 1).getMaxScore();
            BigDecimal currMin = uniqueRanges.get(i).getMinScore();
            if (currMin.subtract(prevMax).compareTo(new BigDecimal("0.01")) != 0) noGaps     = false;
            if (currMin.compareTo(prevMax) <= 0)                                  noOverlaps = false;
        }
        boolean coversFullRange = !uniqueRanges.isEmpty()
                && uniqueRanges.get(0).getMinScore().compareTo(BigDecimal.ZERO) == 0
                && uniqueRanges.get(uniqueRanges.size() - 1).getMaxScore().compareTo(new BigDecimal("100")) == 0;

        // Delete existing global mappings then recreate
        mappingRepository.deleteByTenantIdIsNull();

        List<RiskTemplateMapping> saved = new ArrayList<>();
        for (RiskMappingConfigRequest.MappingEntry e : sorted) {
            saved.add(mappingRepository.save(
                    RiskTemplateMapping.builder()
                            .tenantId(null)
                            .minScore(e.getMinScore())
                            .maxScore(e.getMaxScore())
                            .tierId(e.getTierId())
                            .templateId(e.getTemplateId())
                            .tierLabel(e.getTierLabel())
                            .build()
            ));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "scope",           "GLOBAL",
                "mappingsCreated", saved.size(),
                "validation",      Map.of(
                        "noGaps",          noGaps,
                        "noOverlaps",      noOverlaps,
                        "coversFullRange", coversFullRange)
        )));
    }

    @GetMapping
    @Operation(summary = "Get current global risk-to-template mappings")
    public ResponseEntity<ApiResponse<List<RiskMappingResponse>>> getMappings() {
        // Global mappings have tenant_id = NULL
        List<RiskMappingResponse> result = mappingRepository.findByTenantIdIsNull().stream()
                .map(m -> RiskMappingResponse.builder()
                        .mappingId(m.getId())
                        .minScore(m.getMinScore())
                        .maxScore(m.getMaxScore())
                        .tierId(m.getTierId())
                        .tierLabel(m.getTierLabel())
                        .templateId(m.getTemplateId())
                        .build())
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}