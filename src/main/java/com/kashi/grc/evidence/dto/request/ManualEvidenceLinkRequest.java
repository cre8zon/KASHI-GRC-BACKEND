package com.kashi.grc.evidence.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request to manually link an existing EvidenceRecord to a target entity.
 *
 * Used when auto-propagation didn't pick up the entity (e.g. no controlTag,
 * or the user wants to link evidence from a different tag to this control).
 *
 * Creates an EvidenceLink with autoLinked=false, status=ACCEPTED immediately
 * (manual links don't need PENDING_REVIEW — the user is consciously linking it).
 *
 * targetEntityType: AUDIT_CONTROL_INSTANCE | ASSESSMENT_QUESTION_INSTANCE | ISSUE | etc.
 * targetEntityId:   the entity's primary key
 */
@Data
public class ManualEvidenceLinkRequest {

    @NotBlank
    private String targetEntityType;

    @NotNull
    private Long targetEntityId;

    private String note;
}
