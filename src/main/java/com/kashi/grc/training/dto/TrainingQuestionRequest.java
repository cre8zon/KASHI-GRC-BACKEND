package com.kashi.grc.training.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/training/courses/{id}/questions — the training_question_form.
 *
 * Flat on purpose: four option strings and a number saying which is right.
 * A nested question-with-options editor would have needed a bespoke React
 * component; the service expands this into one question row and up to four
 * option rows instead.
 */
@Getter @Setter
public class TrainingQuestionRequest {

    @NotBlank(message = "Question text is required")
    private String questionText;

    private String questionType;

    /** "1".."4" — which option is correct. Never echoed back to a learner. */
    @NotBlank(message = "Mark which option is correct")
    private String correctOption;

    @NotBlank(message = "At least two options are required")
    @Size(max = 1000)
    private String option1;

    @NotBlank(message = "At least two options are required")
    @Size(max = 1000)
    private String option2;

    @Size(max = 1000) private String option3;
    @Size(max = 1000) private String option4;

    private String explanation;

    private Integer sortOrder;
}
