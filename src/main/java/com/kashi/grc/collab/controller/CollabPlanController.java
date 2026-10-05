package com.kashi.grc.collab.controller;

import com.kashi.grc.collab.service.CollabPlanService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Collaboration plan — phase 2.
 *
 *   GET    /v1/collab/workspaces/{id}/plan?programmeId=           plan items the caller can see
 *   POST   /v1/collab/workspaces/{id}/plan/items                  create (plan editors)
 *   PATCH  /v1/collab/workspaces/{id}/plan/items/{itemId}         update (editors; owners: status/progress)
 *   DELETE /v1/collab/workspaces/{id}/plan/items/{itemId}?reason=
 *   GET    /v1/collab/workspaces/{id}/plan/items/{itemId}/history
 *   GET    /v1/collab/workspaces/{id}/plan/linkable-engagements
 *   PUT    /v1/collab/workspaces/{id}/plan/columns                { columns: [...] } sheet layout (plan editors)
 *   POST   /v1/collab/workspaces/{id}/plan/reorder                { ids: [...] } row order (plan editors)
 *   GET    /v1/collab/workspaces/{id}/plan/export?programmeId=    .xlsx
 *   POST   /v1/collab/workspaces/{id}/plan/import/preview         multipart file + programmeId
 *   POST   /v1/collab/workspaces/{id}/plan/import                 multipart file + programmeId
 *   GET    /v1/collab/engagements/{engagementId}/timeline         the engagement's part of every plan
 *   GET    /v1/collab/my-week                                     my items and milestones, next 14 days
 *
 * Rules are in CollabPlanService (who edits) and CollabAccessService (who sees).
 */
@RestController
@RequestMapping("/v1/collab")
@RequiredArgsConstructor
@Tag(name = "Collaboration plan", description = "Workspace plans and timelines")
public class CollabPlanController {

    private final CollabPlanService service;

    @GetMapping("/workspaces/{id}/plan")
    @Operation(summary = "The workspace plan, as the caller may see it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> plan(@PathVariable Long id,
                                                                 @RequestParam(required = false) Long programmeId) {
        return ResponseEntity.ok(ApiResponse.success(service.plan(id, programmeId)));
    }

    @PostMapping("/workspaces/{id}/plan/items")
    @Operation(summary = "Add a plan item")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@PathVariable Long id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.createItem(id, body)));
    }

    @PatchMapping("/workspaces/{id}/plan/items/{itemId}")
    @Operation(summary = "Change a plan item")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable Long id, @PathVariable Long itemId,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.updateItem(id, itemId, body)));
    }

    @DeleteMapping("/workspaces/{id}/plan/items/{itemId}")
    @Operation(summary = "Remove a plan item (its children move up a level)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, @PathVariable Long itemId,
                                                    @RequestParam(required = false) String reason) {
        service.deleteItem(id, itemId, reason);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/workspaces/{id}/plan/items/{itemId}/history")
    @Operation(summary = "Who changed what on a plan item")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> history(@PathVariable Long id,
                                                                          @PathVariable Long itemId) {
        return ResponseEntity.ok(ApiResponse.success(service.history(id, itemId)));
    }

    @GetMapping("/workspaces/{id}/plan/linkable-engagements")
    @Operation(summary = "Engagements a plan item can be linked to")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> linkable(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.linkableEngagements(id)));
    }

    @PutMapping("/workspaces/{id}/plan/columns")
    @Operation(summary = "Save the plan sheet's columns: order, names, widths, hidden, and the workspace's own columns")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> saveColumns(@PathVariable Long id,
                                                                              @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.saveColumns(id, body.get("columns"))));
    }

    @PostMapping("/workspaces/{id}/plan/reorder")
    @Operation(summary = "Put plan rows in the given order")
    public ResponseEntity<ApiResponse<Void>> reorder(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        service.reorder(id, body.get("ids"));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/workspaces/{id}/plan/export")
    @Operation(summary = "Download the plan as .xlsx")
    public ResponseEntity<byte[]> export(@PathVariable Long id, @RequestParam(required = false) Long programmeId) {
        byte[] bytes = service.exportXlsx(id, programmeId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"plan-" + id + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping("/workspaces/{id}/plan/import/preview")
    @Operation(summary = "Read a plan spreadsheet and show what would be created (writes nothing)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importPreview(@PathVariable Long id,
                                                                          @RequestParam("file") MultipartFile file,
                                                                          @RequestParam(required = false) Long programmeId) {
        return ResponseEntity.ok(ApiResponse.success(service.importPreview(id, programmeId, file)));
    }

    @PostMapping("/workspaces/{id}/plan/import")
    @Operation(summary = "Import a plan spreadsheet into a programme (all rows or none)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importCommit(@PathVariable Long id,
                                                                         @RequestParam("file") MultipartFile file,
                                                                         @RequestParam(required = false) Long programmeId) {
        return ResponseEntity.ok(ApiResponse.success(service.importCommit(id, programmeId, file)));
    }

    @GetMapping("/engagements/{engagementId}/timeline")
    @Operation(summary = "Plan items linked to this engagement, and everything under them")
    public ResponseEntity<ApiResponse<Map<String, Object>>> engagementTimeline(@PathVariable Long engagementId) {
        return ResponseEntity.ok(ApiResponse.success(service.engagementTimeline(engagementId)));
    }

    @GetMapping("/my-week")
    @Operation(summary = "My plan items and milestones due in the next 14 days, and anything overdue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myWeek() {
        return ResponseEntity.ok(ApiResponse.success(service.myWeek()));
    }
}