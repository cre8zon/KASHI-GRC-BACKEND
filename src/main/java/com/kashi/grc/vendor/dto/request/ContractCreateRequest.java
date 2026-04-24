package com.kashi.grc.vendor.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ContractCreateRequest {
    public String contractNumber;
    public String contractType;
    public String status;
    public LocalDate startDate;
    public LocalDate endDate;
    public LocalDate renewalDate;
    public BigDecimal contractValue;
    public Long documentId;
}
