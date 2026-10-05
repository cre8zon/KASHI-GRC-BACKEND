package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.service.AuditSectionSubmissionService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Submit my sections — evidence owners and testers close their own part of an
 * engagement, explaining whatever is left. Rules in AuditSectionSubmissionService.
 * All three are called by ui_actions buttons (requires_remarks collects the reason).
 *
 *   POST /v1/audit/engagements/{id}/submit-sections
 *        { kind: EVIDENCE|TESTING, taskId?, remarks, sectionIds?: [..], reasons?: { controlId: "why" } }
 *        kind left out follows the caller's open task
 *   POST /v1/audit/engagements/{id}/controls/{cid}/submit-without-evidence   { remarks }
 *   POST /v1/audit/engagements/{id}/controls/{cid}/mark-not-tested          { remarks }
 *   POST /v1/audit/engagements/{id}/controls/{cid}/reopen-evidence          { remarks }
 */
@RestController
@RequestMapping("/v1/audit")
@RequiredArgsConstructor
@Tag(name = "Audit Management", description = "Submit my sections")
public class AuditSectionSubmissionController {

    private final AuditSectionSubmissionService service;

    @PostMapping("/engagements/{id}/submit-sections")
    @Operation(summary = "Submit my sections, with a reason for every control left without evidence or untested")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(@PathVariable Long id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.submit(id, body)));
    }

    @PostMapping("/engagements/{id}/controls/{cid}/submit-without-evidence")
    @Operation(summary = "Submit one control without evidence, with a reason")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitWithoutEvidence(@PathVariable Long id, @PathVariable Long cid,
                                                                                  @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.submitWithoutEvidence(id, cid, body)));
    }

    @PostMapping("/engagements/{id}/controls/{cid}/reopen-evidence")
    @Operation(summary = "Evidence side: ask for submitted evidence to be redone, before the auditor concludes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reopenEvidence(@PathVariable Long id, @PathVariable Long cid,
                                                                           @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.reopenEvidence(id, cid, body)));
    }

    @PostMapping("/engagements/{id}/controls/{cid}/mark-not-tested")
    @Operation(summary = "Leave one control untested, with a reason")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markNotTested(@PathVariable Long id, @PathVariable Long cid,
                                                                          @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.markNotTested(id, cid, body)));
    }
}