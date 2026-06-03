package com.kashi.grc.evidence.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request to accept or reject an auto-linked EvidenceLink.
 *
 * Called by the auditor/reviewer from the control detail page
 * when they see a PENDING_REVIEW auto-linked evidence item.
 *
 * action: ACCEPT or REJECT
 * note:   required on REJECT (must explain why evidence doesn't satisfy the control)
 *         optional on ACCEPT
 */
@Data
public class EvidenceLinkReviewRequest {

    @NotNull
    private Action action;

    private String note;

    public enum Action { ACCEPT, REJECT }
}
