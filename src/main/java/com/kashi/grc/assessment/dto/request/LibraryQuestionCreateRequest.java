package com.kashi.grc.assessment.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

/**
 * Request DTO for creating or updating a library question.
 *
 * questionTag is the semantic category used by KashiGuard for rule matching.
 * It is optional — untagged questions are never evaluated by the guard system.
 * Set it when the question's answer should trigger automatic findings/action items.
 *
 * Examples: MFA, ENCRYPTION, PEN_TEST, IRP, BCP, DRP, DATA_RETENTION,
 *           DPA, CERTIFICATION, VULN_MGMT, SEC_TRAINING, CISO,
 *           BREACH_NOTIFY, SUBPROCESSOR, INFOSEC_POLICY
 */
@Data
public class LibraryQuestionCreateRequest {

    @NotBlank
    public String questionText;

    @NotBlank
    public String responseType;

    public List<Long> optionIds;

    /**
     * Optional. Guard rule category tag.
     * If set, this question (and all its future assessment instances) will be
     * evaluated against all active GuardRules whose questionTag matches this value.
     * If null or blank, the guard system will skip this question entirely.
     */
    public String questionTag;
}