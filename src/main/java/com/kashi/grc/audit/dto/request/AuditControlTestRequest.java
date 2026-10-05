package com.kashi.grc.audit.dto.request;

import com.kashi.grc.audit.domain.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AuditControlTestRequest {
    @NotNull
    private AuditControlInstance.TestResult testResult;
    private String testNotes;
    private String testProcedure;
    private Long   findingIssueId;   // Issue.id if a finding was raised

    // Evidence gate escape hatch. Inquiry- and observation-only procedures can
    // legitimately conclude with no artifact; requiring a reason keeps that
    // visible instead of silently allowing unsupported conclusions.
    private Boolean evidenceOverride;
    private String  evidenceOverrideReason;
}