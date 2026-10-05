package com.kashi.grc.personnel.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** POST /v1/personnel/{id}/assets — opened by PERSON_ASSIGN_ASSET via __formKey. */
@Getter
@Setter
public class PersonnelAssetRequest {

    @NotNull(message = "assetId is required")
    private Long assetId;

    /** DATE or ISO datetime. Defaults to now. */
    private String assignedAt;

    @Size(max = 500)
    private String assignmentNote;
}
