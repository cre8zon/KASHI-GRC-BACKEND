package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiActionResponse {
    private Long    id;
    private String  actionKey;
    private String  label;
    private String  icon;
    private String  variant;
    private String  apiEndpoint;
    private String  httpMethod;
    private String  payloadTemplateJson;
    private String  allowedStatusesJson;
    private boolean requiresConfirmation;
    private String  confirmationMessage;
    private boolean requiresRemarks;
    private boolean requiresAssignment;
    /** See UiAction.requiresSectionGate. */
    private boolean requiresSectionGate;
    /**
     * See UiAction.allowedStepActions. Comma-separated, OR semantics, null =
     * any step. Sent as the raw string so the client splits it the same way it
     * already splits allowed_sides, rather than the server inventing a second
     * shape for the same idea.
     */
    private String  allowedStepActions;
    /**
     * See UiAction.completesSectionKey. Matched against
     * AccessContext.openSectionKeys — the action shows only while that gate is
     * still owed. Null = not tied to a gate.
     */
    private String  completesSectionKey;
    private Integer sortOrder;
}