package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionInstanceResponse {
    private Long   questionInstanceId;
    private String questionText;
    private String responseType;
    private Double weight;
    private boolean mandatory;
    private Integer orderNo;
    private List<OptionInstanceResponse> options;
    private AnswerResponse currentResponse;

    // ── VENDOR-SIDE CONTRIBUTOR ASSIGNMENT (step 4) ───────────────────────────
    // Set by Responder when assigning a question to a Contributor for answering.
    // Used by contributor inbox (my-questions) and re-answer flows.
    // NEVER overwritten by org-side review actions.
    private Long   assignedUserId;
    private String assignedUserName;

    // ── ORG-SIDE REVIEW ASSISTANT ASSIGNMENT (step 9) ─────────────────────────
    // Set by Reviewer when delegating a question to a review assistant.
    // Completely independent from assignedUserId — survives across send-back cycles.
    private Long   reviewerAssignedUserId;
    private String reviewerAssignedUserName;

    // Section context — needed by contributor view to group questions by section
    private Long   sectionInstanceId;
    private String sectionName;
    // null = section not yet submitted (editable); non-null = locked
    private LocalDateTime sectionSubmittedAt;
}