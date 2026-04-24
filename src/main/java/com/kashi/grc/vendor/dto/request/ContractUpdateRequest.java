package com.kashi.grc.vendor.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ContractUpdateRequest {
    public String status;
    public LocalDate endDate;
    public LocalDate renewalDate;
    public BigDecimal contractValue;
}
