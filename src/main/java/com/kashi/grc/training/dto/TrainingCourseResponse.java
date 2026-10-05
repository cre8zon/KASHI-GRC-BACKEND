package com.kashi.grc.training.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** Detail payload for GET /v1/training/courses/{id}. */
@Getter @Builder
public class TrainingCourseResponse {

    private Long    id;
    private String  courseRef;
    private String  title;
    private String  description;
    private String  category;
    private String  status;
    private Integer estimatedMinutes;
    private Integer requiredWatchPercent;
    private Integer quizPassPercent;
    private Boolean requiresAttestation;
    private Integer recurrenceMonths;
    private String  controlTags;
    private String  frameworkRefs;
    private LocalDateTime publishedAt;

    /** "GLOBAL" for a platform library course, "ORG" for the tenant's own. */
    private String  origin;

    /** False for a library course — a tenant works on its own, or assigns this one. */
    private Boolean editable;

    private Integer itemCount;
    private Integer questionCount;
    private Integer assignedCount;

    /** Total video seconds across required items, for the learner's expectation. */
    private Integer totalDurationSeconds;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<Item> items;

    /**
     * A content item as an AUTHOR sees it. The learner gets
     * TrainingAssignmentResponse.PlayerItem instead, which carries a presigned
     * URL and no s3Key.
     */
    @Getter @Builder
    public static class Item {
        private Long    id;
        private String  ref;          // item type, for LinkedEntitiesTab
        private String  title;
        private String  status;       // READY / INCOMPLETE
        private String  badge;        // duration or file size
        private String  linkNote;
        private String  itemType;
        private Integer durationSeconds;
        private Integer sortOrder;
        private Boolean isRequired;
    }
}
