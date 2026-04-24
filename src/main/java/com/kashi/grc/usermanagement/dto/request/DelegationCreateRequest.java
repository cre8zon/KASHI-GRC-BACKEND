package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class DelegationCreateRequest {
    @NotNull
    public Long delegateeUserId;
    public String scopeType;
    public Long scopeId;
    public String startDate;
    public String endDate;
    public List<String> permissionsDelegated;
    public String notes;
}
