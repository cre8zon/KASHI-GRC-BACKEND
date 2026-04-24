package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionResponse {
    private Long    questionId;
    private String  questionText;
    private String  responseType;
    /**
     * weight and isMandatory come from SectionQuestionMapping — they represent
     * this question's behaviour in the context of a specific section.
     * Null when viewing the question standalone in the library.
     */
    private Double  weight;
    private boolean isMandatory;
    private Integer orderNo;
    private List<OptionResponse> options;
}