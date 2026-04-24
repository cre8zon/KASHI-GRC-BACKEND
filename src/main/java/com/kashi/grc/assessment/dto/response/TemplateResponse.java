package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TemplateResponse {
    private Long           templateId;
    private String         name;
    private Integer        version;
    private String         status;          // DRAFT | PUBLISHED
    private LocalDateTime  publishedAt;
    private LocalDateTime  unpublishedAt;   // set when Platform Admin reverts to DRAFT
    private LocalDateTime  createdAt;
    private List<SectionResponse> sections; // only populated on /full endpoint
}