package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LibraryQuestionResponse {
    private Long   questionId;
    private String questionText;
    private String responseType;
    /** Guard rule category tag — null means question is not evaluated by KashiGuard */
    private String questionTag;
    /** Number of options linked via question_option_mappings */
    private int    optionsLinked;
    /** Number of sections this question is currently mapped into */
    private int    sectionsUsedIn;
}