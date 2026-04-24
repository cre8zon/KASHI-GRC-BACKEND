package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorUserResponse {

    private Long userId;
    private Long vendorUserId;
    private String email;
    private String fullName;
    private String vendorRole;
    private String status;
    private VendorRef vendor;
    private Boolean isPrimaryContact;
    private Boolean inviteSent;
    private Boolean passwordResetRequired;
    private String temporaryPassword;
    private LocalDateTime createdAt;

    // ── Nested Class ──────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VendorRef {
        private Long vendorId;
        private String vendorName;
    }
}
