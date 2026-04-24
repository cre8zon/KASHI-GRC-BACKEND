package com.kashi.grc.vendor.service;

import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.domain.RiskTemplateMapping;
import com.kashi.grc.vendor.dto.response.RiskScoreResponse;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorRiskService {

    private final VendorRepository vendorRepository;
    private final RiskTemplateMappingRepository mappingRepository;

    @Transactional
    public RiskScoreResponse calculateAndPersist(Vendor vendor) {
        BigDecimal score = calculate(
                vendor.getDataAccessLevel(), vendor.getRiskClassification(),
                vendor.getCriticality(), vendor.getIndustry());
        BigDecimal previous = vendor.getCurrentRiskScore();
        vendor.setCurrentRiskScore(score);
        vendorRepository.save(vendor);

        Optional<RiskTemplateMapping> mapping = mappingRepository.findByScore(score);
        Map<String, Object> recommendedTier = null;
        Map<String, Object> recommendedTemplate = null;
        if (mapping.isPresent()) {
            var m = mapping.get();
            recommendedTier = Map.of(
                "tierId",    m.getTierId() != null ? m.getTierId() : 0,
                "tierLabel", m.getTierLabel() != null ? m.getTierLabel() : "");
            recommendedTemplate = Map.of("templateId", m.getTemplateId());
        }
        return RiskScoreResponse.builder()
                .vendorId(vendor.getId()).vendorName(vendor.getName())
                .previousRiskScore(previous).newRiskScore(score)
                .riskClassification(classifyScore(score))
                .scoreBreakdown(breakdown(vendor.getDataAccessLevel(),
                        vendor.getRiskClassification(), vendor.getCriticality(), vendor.getIndustry()))
                .recommendedTier(recommendedTier).recommendedTemplate(recommendedTemplate)
                .build();
    }

    public BigDecimal calculate(String dal, String rc, String criticality, String industry) {
        double score = 0;
        score += switch (dal != null ? dal : "") {
            case "RESTRICTED"   -> 40.0;
            case "CONFIDENTIAL" -> 35.0;
            case "INTERNAL"     -> 25.0;
            case "PUBLIC"       -> 10.0;
            default             -> 0.0;
        };
        score += switch (rc != null ? rc : "") {
            case "CRITICAL" -> 30.0; case "HIGH"   -> 22.5;
            case "MEDIUM"   -> 15.0; case "LOW"    -> 7.5;
            default         -> 0.0;
        };
        score += switch (criticality != null ? criticality : "") {
            case "CRITICAL" -> 30.0; case "HIGH"   -> 22.5;
            case "MEDIUM"   -> 15.0; case "LOW"    -> 7.5;
            default         -> 0.0;
        };
        score += switch (industry != null ? industry : "") {
            case "Healthcare", "Finance" -> 5.0;
            case "Retail"               -> 2.0;
            case "Consulting"           -> -2.0;
            default                     -> 0.0;
        };
        score = Math.max(0, Math.min(100, score));
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    private String classifyScore(BigDecimal score) {
        double v = score.doubleValue();
        if (v >= 85) return "CRITICAL";
        if (v >= 60) return "HIGH";
        if (v >= 25) return "MEDIUM";
        return "LOW";
    }

    private Map<String, Object> breakdown(String dal, String rc, String crit, String industry) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataAccessLevelScore", switch (dal != null ? dal : "") {
            case "RESTRICTED" -> 40.0; case "CONFIDENTIAL" -> 35.0;
            case "INTERNAL"   -> 25.0; case "PUBLIC"       -> 10.0; default -> 0.0;
        });
        b.put("riskClassificationScore", switch (rc != null ? rc : "") {
            case "CRITICAL" -> 30.0; case "HIGH" -> 22.5;
            case "MEDIUM"   -> 15.0; case "LOW"  -> 7.5; default -> 0.0;
        });
        b.put("criticalityScore", switch (crit != null ? crit : "") {
            case "CRITICAL" -> 30.0; case "HIGH" -> 22.5;
            case "MEDIUM"   -> 15.0; case "LOW"  -> 7.5; default -> 0.0;
        });
        b.put("industryModifier", switch (industry != null ? industry : "") {
            case "Healthcare", "Finance" -> 5.0;
            case "Retail"   -> 2.0; case "Consulting" -> -2.0; default -> 0.0;
        });
        return b;
    }
}
