package com.kashi.grc.usermanagement.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class UserDeactivateRequest {
    public String reason;
    public List<ReassignmentItem> reassignments;
    public boolean preserveAuditTrail = true;

    @Data
    public static class ReassignmentItem {
        public String type;
        public Long entityId;
        public Long reassignToUserId;
        public String action;
    }
}
