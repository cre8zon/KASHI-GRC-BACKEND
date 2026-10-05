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

    /**
     * Snapshot of whether this question requires a document.
     *
     * Separate from `mandatory`, which means the ANSWER is required. Both can
     * be true, either can be true alone, and the two screens that render them
     * show different markers for that reason.
     */
    private boolean requiresEvidence;

    /**
     * How many live documents are linked to this question as QUESTION_RESPONSE.
     *
     * Needed because requiresEvidence on its own only says the question wants a
     * file; it cannot say whether one arrived. The fill and review screens use
     * the pair to show "Evidence required" in amber and "Evidence attached" in
     * green without opening each question's panel, and it is the same count the
     * submit gate checks — so the badge and the block agree.
     *
     * Zero for questions that neither require evidence nor are FILE_UPLOAD;
     * those are not counted, because counting every question would turn one
     * cheap GROUP BY into a scan of document_links per assessment.
     */
    private int evidenceCount;
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

    // ── KASHIGUARD TAG SNAPSHOT ───────────────────────────────────────────────
    // Copied from AssessmentQuestionInstance.questionTagSnapshot, which is what
    // GuardEvaluator matches on. It is a SNAPSHOT and is deliberately never
    // joined back to the question library: AssessmentExecutionService relies on
    // an in-flight assessment being unaffected by later library edits. Without
    // this field the guard badge on every question row renders undefined.
    private String questionTag;

    // Section context — needed by contributor view to group questions by section
    private Long   sectionInstanceId;
    private String sectionName;
    // null = section not yet submitted (editable); non-null = locked
    private LocalDateTime sectionSubmittedAt;
}