package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SectionResponse {
    private Long   sectionId;
    private String name;
    /**
     * Order of this section within its template.
     * Comes from TemplateSectionMapping.orderNo — not stored on the section itself.
     * Null when viewing a section standalone in the library.
     */
    private Integer orderNo;
    private List<QuestionResponse> questions;
}