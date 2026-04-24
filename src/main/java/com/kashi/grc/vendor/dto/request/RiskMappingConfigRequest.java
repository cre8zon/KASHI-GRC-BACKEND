package com.kashi.grc.vendor.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class RiskMappingConfigRequest {
    @NotNull @Valid public List<MappingEntry> mappings;

    @Data
    public static class MappingEntry {
        @NotNull public BigDecimal minScore;
        @NotNull public BigDecimal maxScore;
        public Long tierId;
        @NotNull public Long templateId;
        public String tierLabel;
    }
}
