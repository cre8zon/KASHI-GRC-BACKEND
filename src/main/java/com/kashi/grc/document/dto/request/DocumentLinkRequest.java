package com.kashi.grc.document.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentLinkRequest {
    @NotNull public Long documentId;
    public Integer version;
}
