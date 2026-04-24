package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityLogEvent {

    private Long logId;
    private String action;
    private String result;
    private String ipAddress;
    private String resourceType;
    private Long resourceId;
    private LocalDateTime timestamp;
    private Map<String, String> metadata;
}
