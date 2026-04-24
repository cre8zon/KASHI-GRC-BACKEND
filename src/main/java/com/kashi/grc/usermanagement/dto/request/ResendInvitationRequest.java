package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResendInvitationRequest {
    @NotNull
    private Long    userId;
    @NotBlank private String  email;
    private   boolean         sendEmail = true;
}
