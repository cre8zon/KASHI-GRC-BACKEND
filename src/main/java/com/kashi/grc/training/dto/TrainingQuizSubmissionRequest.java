package com.kashi.grc.training.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * POST /v1/training/assignments/{id}/quiz — submit an attempt.
 *
 * questionId -> chosen option ids. Grading happens server-side against options
 * loaded fresh from the database; the submission carries no correctness
 * information and none is accepted if it does.
 */
@Getter @Setter
public class TrainingQuizSubmissionRequest {

    @NotEmpty(message = "Answer at least one question")
    private Map<Long, List<Long>> answers;
}
