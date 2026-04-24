package com.kashi.grc.document.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentResponse {
    private Long documentId;
    private String fileName;
    private Long fileSize;
    private String fileHash;
    private String storagePath;
    private Long uploadedBy;
    private String mimeType;
    private LocalDateTime createdAt;
}
