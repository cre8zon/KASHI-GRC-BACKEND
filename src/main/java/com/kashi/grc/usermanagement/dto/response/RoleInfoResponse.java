package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoleInfoResponse {

    private Long roleId;
    private String roleName;
    private String side;
    private String level;
    private Integer permissionsCount;
    private Long userCount;
}
