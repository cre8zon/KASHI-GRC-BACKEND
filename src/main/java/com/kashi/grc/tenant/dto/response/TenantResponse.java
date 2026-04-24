package com.kashi.grc.tenant.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantResponse {
    private Long tenantId;
    private String name;
    private String code;
    private String description;
    private String status;
    private String plan;
    private Integer maxUsers;
    private Integer maxVendors;
    private LocalDateTime createdAt;
}
