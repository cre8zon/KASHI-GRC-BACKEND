package com.kashi.grc.training.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** Create and update for training_course_create_form / _header / _tab_overview. */
@Getter @Setter
public class TrainingCourseRequest {

    @NotBlank(message = "Course title is required")
    @Size(max = 500)
    private String title;

    private String description;

    @Size(max = 30)
    private String courseRef;

    @Size(max = 50)
    private String category;

    /** Accepted only when equal to the current status — Publish/Archive own it. */
    private String status;

    @Min(1) @Max(600)
    private Integer estimatedMinutes;

    @Min(1) @Max(100)
    private Integer requiredWatchPercent;

    /** Null means no quiz. Zero would mean a quiz nobody can fail. */
    @Min(1) @Max(100)
    private Integer quizPassPercent;

    @Min(0) @Max(120)
    private Integer recurrenceMonths;

    private Boolean requiresAttestation;

    /** TAG fields — comma-separated on arrival. */
    @Size(max = 500) private String controlTags;
    @Size(max = 500) private String frameworkRefs;
}
