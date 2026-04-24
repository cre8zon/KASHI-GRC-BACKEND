package com.kashi.grc.vendor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContractResponse {
    private Long contractId;
    private Long vendorId;
    private String contractNumber;
    private String contractType;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate renewalDate;
    private BigDecimal contractValue;
    private Long documentId;
    private LocalDateTime createdAt;
}
