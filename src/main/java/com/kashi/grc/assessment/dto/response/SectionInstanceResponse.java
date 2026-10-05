package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SectionInstanceResponse {
    private Long          sectionInstanceId;
    private String        sectionName;
    private Integer       sectionOrderNo;
    private Long          assignedUserId;
    private String        assignedUserName;
    // Org-side reviewer assignment (ReviewController writes reviewerAssignedUserId).
    // Exposed so AssignReviewersPanel can seed committed state on refresh — without
    // this the panel showed "0/N assigned" after every reload.
    private Long          reviewerAssignedUserId;
    private String        reviewerAssignedUserName;
    // Submission state — null means editable, non-null means locked
    private LocalDateTime submittedAt;
    private Long          submittedBy;
    private String        submittedByName;
    private LocalDateTime reopenedAt;

    /**
     * The REVIEWER's lock on this section, distinct from submittedAt which is
     * the vendor responder's.
     *
     * Needed by the review assistant's screen: once the reviewer has locked a
     * section, the assistant cannot change a verdict in it, and a UI that
     * cannot see this state either lets them try and fail, or silently narrows
     * on a field that is always undefined. Cleared by reviewer-reopen, which
     * is the org CISO's gesture.
     */
    private LocalDateTime reviewerSubmittedAt;
    private LocalDateTime reviewerReopenedAt;
    private List<QuestionInstanceResponse> questions;
}