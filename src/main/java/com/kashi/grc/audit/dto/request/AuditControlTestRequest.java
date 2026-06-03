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
}
