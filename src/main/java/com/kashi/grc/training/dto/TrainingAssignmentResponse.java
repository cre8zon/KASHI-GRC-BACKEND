package com.kashi.grc.training.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What a learner sees. GET /v1/training/assignments/{id}.
 *
 * ── WHAT IS DELIBERATELY ABSENT ───────────────────────────────────────────
 * No s3Key, and no isCorrect anywhere. Items carry a short-TTL presigned URL
 * generated per request, and quiz options carry id and text only. Putting
 * either on this DTO would make the quiz answerable from devtools and the
 * video downloadable forever, and every completion record on the system would
 * stop being evidence.
 */
@Getter @Builder
public class TrainingAssignmentResponse {

    private Long    id;
    private Long    personnelId;
    private String  personName;
    private String  targetType;
    private Long    targetId;
    private Integer targetVersion;
    private String  targetTitle;
    private String  status;

    private LocalDateTime assignedAt;
    private LocalDateTime dueAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime attestedAt;

    /** Derived from dueAt, never stored — see TrainingAssignment.isOverdue. */
    private Boolean overdue;

    private Integer quizScorePercent;
    private Integer quizAttempts;

    // ── What the learner still has to do ──────────────────────────────────────
    private Integer progressPercent;
    private Boolean allContentComplete;
    private Boolean quizRequired;
    private Boolean quizPassed;
    private Boolean attestationRequired;

    /** Course settings the player needs, flattened so it needs one call. */
    private Integer requiredWatchPercent;
    private Integer quizPassPercent;
    private String  courseDescription;

    private List<PlayerItem> items;
    private List<QuizQuestion> quiz;

    /** One content item, with a presigned URL that expires. */
    @Getter @Builder
    public static class PlayerItem {
        private Long    id;
        private String  itemType;
        private String  title;
        private Integer sortOrder;
        private Boolean isRequired;
        private Integer durationSeconds;

        /** Short-TTL presigned GET. Regenerated on every read, never stored. */
        private String  playbackUrl;
        private String  externalUrl;
        private String  body;

        // Progress
        private Integer maxPositionSeconds;
        private Integer watchedSeconds;
        private Boolean completed;
    }

    /** A question as the learner sees it — no correctness information. */
    @Getter @Builder
    public static class QuizQuestion {
        private Long   id;
        private String questionText;
        private String questionType;
        private List<QuizOption> options;
    }

    @Getter @Builder
    public static class QuizOption {
        private Long   id;
        private String optionText;
    }
}
