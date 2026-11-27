package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.service.AuditPolicyRevisionService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Draft revisions of a policy body: list, read, snapshot, restore.
 * Kept out of AuditPolicyController, which is already 1,500 lines; the access
 * rules are the same and live in AuditPolicyRevisionService.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Audit Policy Revisions")
public class AuditPolicyRevisionController {

    private final AuditPolicyRevisionService revisions;

    @GetMapping("/v1/audit/library/policies/{id}/revisions")
    @Operation(summary = "Draft revisions of this policy, newest first (no bodies)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(revisions.list(id)));
    }

    @GetMapping("/v1/audit/library/policies/{id}/revisions/{revisionId}")
    @Operation(summary = "One revision, with its body")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id,
                                                                @PathVariable Long revisionId) {
        return ResponseEntity.ok(ApiResponse.success(revisions.get(id, revisionId)));
    }

    @PostMapping("/v1/audit/library/policies/{id}/revisions")
    @Operation(summary = "Snapshot a body as a revision (deduplicated against the latest)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> snapshot(@PathVariable Long id,
                                                                     @RequestBody(required = false) SnapshotRequest req) {
        SnapshotRequest r = req == null ? new SnapshotRequest() : req;
        return ResponseEntity.ok(ApiResponse.success(
                revisions.snapshot(id, r.getSource(), r.getContentBody(), r.getAiInteractionId(), r.getNote())));
    }

    @PostMapping("/v1/audit/library/policies/{id}/revisions/{revisionId}/restore")
    @Operation(summary = "Put an older revision's body back on the policy; the current body is kept as a revision first")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restore(@PathVariable Long id,
                                                                    @PathVariable Long revisionId) {
        return ResponseEntity.ok(ApiResponse.success(revisions.restore(id, revisionId)));
    }

    @Data
    public static class SnapshotRequest {
        private String source;
        private String contentBody;
        private Long   aiInteractionId;
        private String note;
    }
}
