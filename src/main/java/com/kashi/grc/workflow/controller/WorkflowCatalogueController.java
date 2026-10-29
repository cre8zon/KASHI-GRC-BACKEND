package com.kashi.grc.workflow.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.enquiry.service.EnquiryService;
import com.kashi.grc.workflow.service.WorkflowCatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Workflow catalogue — the "see the workflow before you start" screen.
 *
 *   GET  /v1/workflow-catalogue?entityType=ISSUE   every workflow a module can run
 *   GET  /v1/workflow-catalogue/{id}                one, with role coverage for the tenant
 *   GET  /v1/workflow-catalogue/{id}/demo           where to play its walkthrough + chapters
 *   POST /v1/workflow-catalogue/requests            ask for a custom workflow (→ Enquiries)
 *
 * Platform Admin only:
 *   POST /v1/workflow-catalogue/{id}/demo/upload-url   presigned PUT for the video
 *   PUT  /v1/workflow-catalogue/{id}/demo               video source + chapter times
 */
@RestController
@RequestMapping("/v1/workflow-catalogue")
@Tag(name = "Workflow catalogue", description = "Workflows a module can run, their steps, roles and walkthroughs")
@RequiredArgsConstructor
public class WorkflowCatalogueController {

    private final WorkflowCatalogueService catalogueService;
    private final EnquiryService           enquiryService;
    private final UtilityService           utilityService;

    @GetMapping
    @Operation(summary = "Workflows a module can run — steps, sides, roles, usage")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@RequestParam String entityType) {
        return ResponseEntity.ok(ApiResponse.success(catalogueService.list(entityType)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One workflow with role coverage for the caller's organisation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(catalogueService.get(id)));
    }

    @GetMapping("/{id}/demo")
    @Operation(summary = "Walkthrough video source and chapter times")
    public ResponseEntity<ApiResponse<Map<String, Object>>> demo(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(catalogueService.demo(id)));
    }

    @PostMapping("/requests")
    @Operation(summary = "Request a custom workflow — lands in the platform Enquiries inbox")
    public ResponseEntity<ApiResponse<Map<String, Object>>> request(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = enquiryService.submitWorkflowRequest(
                utilityService.getLoggedInDataContext(),
                str(body.get("entityType")), str(body.get("moduleLabel")),
                str(body.get("startFrom")), str(body.get("neededBy")), str(body.get("message")));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(out));
    }

    @PostMapping("/{id}/demo/upload-url")
    @Operation(summary = "Platform Admin: presigned PUT URL for a walkthrough video")
    public ResponseEntity<ApiResponse<Map<String, Object>>> demoUploadUrl(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long size = body.get("fileSizeBytes") instanceof Number n ? n.longValue() : null;
        return ResponseEntity.ok(ApiResponse.success(catalogueService.demoUploadUrl(
                id, str(body.get("fileName")), str(body.get("mimeType")), size)));
    }

    @PutMapping("/{id}/demo")
    @Operation(summary = "Platform Admin: set the walkthrough video and chapter times")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveDemo(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(catalogueService.saveDemo(id, body)));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
