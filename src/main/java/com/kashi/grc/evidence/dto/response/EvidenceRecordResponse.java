package com.kashi.grc.evidence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.evidence.domain.EvidenceLink;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response for a single EvidenceRecord.
 * Includes denormalized link count and optionally the links themselves.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceRecordResponse {

    private Long   id;
    private Long   tenantId;
    private String title;
    private String description;
    private String controlTag;
    private String fileUrl;
    private String fileName;
    private Long   fileSizeBytes;
    private String mimeType;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private boolean expired;
    private String sourceEntityType;
    private Long   sourceEntityId;
    private Long   uploadedBy;
    private LocalDateTime uploadedAt;
    private Integer linkCount;

    /** Populated only on GET /v1/evidence/{id} — not on list endpoints */
    private List<EvidenceLinkResponse> links;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
