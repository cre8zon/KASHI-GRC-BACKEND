package com.kashi.grc.workflow.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.SecurityContextHelper;
import com.kashi.grc.workflow.dto.response.TaskSectionProgressResponse;
import com.kashi.grc.workflow.domain.TaskSectionAssignment;
import com.kashi.grc.workflow.domain.TaskSectionItem;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for compound task operations — sections, draft, assignment, items.
 * Base path: /v1/compound-tasks
 */
@RestController
@RequestMapping("/v1/compound-tasks")
@RequiredArgsConstructor
public class CompoundTaskController {

    private final TaskSectionCompletionService sectionService;
    private final SecurityContextHelper        securityHelper;

    // ── Progress ──────────────────────────────────────────────────

    @GetMapping("/{taskInstanceId}/progress")
    public ApiResponse<List<TaskSectionProgressResponse>> getProgress(
            @PathVariable Long taskInstanceId) {
        return ApiResponse.success(sectionService.getProgress(taskInstanceId));
    }

    // ── Draft ─────────────────────────────────────────────────────

    @PostMapping("/{taskInstanceId}/draft")
    public ApiResponse<Void> saveDraft(
            @PathVariable Long taskInstanceId,
            @RequestBody String draftJson) {
        sectionService.saveDraft(taskInstanceId, draftJson, securityHelper.userId());
        return ApiResponse.success(null);
    }

    @GetMapping("/{taskInstanceId}/draft")
    public ApiResponse<String> getDraft(@PathVariable Long taskInstanceId) {
        return ApiResponse.success(sectionService.getDraft(taskInstanceId));
    }

    // ── Case 2: section-level assignment ──────────────────────────

    @PostMapping("/{taskInstanceId}/sections/{sectionKey}/assign")
    public ApiResponse<List<SectionAssignmentResponse>> assignSection(
            @PathVariable Long taskInstanceId,
            @PathVariable String sectionKey,
            @Valid @RequestBody SectionAssignRequest req) {
        Long userId = securityHelper.userId();
        List<TaskSectionAssignment> assignments = sectionService.assignSection(
                taskInstanceId, sectionKey, req.getAssigneeUserIds(), userId, req.getNotes());
        List<SectionAssignmentResponse> response = assignments.stream()
                .map(a -> new SectionAssignmentResponse(
                        a.getId(), a.getTaskInstanceId(), a.getSectionKey(),
                        a.getAssignedToUserId(), a.getSubTaskInstanceId(), a.getStatus()))
                .toList();
        return ApiResponse.success(response);
    }

    @PostMapping("/sub-tasks/{subTaskInstanceId}/complete")
    public ApiResponse<Void> completeSubTask(@PathVariable Long subTaskInstanceId) {
        sectionService.onSubTaskCompleted(subTaskInstanceId, securityHelper.userId());
        return ApiResponse.success(null);
    }

    // ── Case 3: item-level ────────────────────────────────────────

    @PostMapping("/{taskInstanceId}/sections/{sectionKey}/items")
    public ApiResponse<List<TaskSectionItemResponse>> registerItems(
            @PathVariable Long taskInstanceId,
            @PathVariable String sectionKey,
            @RequestBody List<ItemRegistrationRequest> items) {
        List<TaskSectionCompletionService.ItemRegistration> registrations = items.stream()
                .map(i -> new TaskSectionCompletionService.ItemRegistration(
                        i.getItemRefType(), i.getItemRefId(), i.getLabel()))
                .toList();
        List<TaskSectionItem> registered = sectionService.registerItems(
                taskInstanceId, sectionKey, registrations);
        List<TaskSectionItemResponse> response = registered.stream()
                .map(item -> new TaskSectionItemResponse(item.getId(), item.getSectionKey(),
                        item.getItemRefType(), item.getItemRefId(),
                        item.getItemLabel(), item.getStatus()))
                .toList();
        return ApiResponse.success(response);
    }

    @PostMapping("/{taskInstanceId}/sections/{sectionKey}/items/assign")
    public ApiResponse<Void> assignItems(
            @PathVariable Long taskInstanceId,
            @PathVariable String sectionKey,
            @Valid @RequestBody ItemAssignRequest req) {
        Long userId = securityHelper.userId();
        sectionService.assignItems(taskInstanceId, sectionKey,
                req.getItemIds(), req.getAssignedToUserId(), userId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskInstanceId}/sections/{sectionKey}/items/{itemId}/complete")
    public ApiResponse<Void> completeItem(
            @PathVariable Long taskInstanceId,
            @PathVariable String sectionKey,
            @PathVariable Long itemId,
            @RequestBody ItemCompletionRequest req) {
        Long userId = securityHelper.userId();
        sectionService.completeItem(taskInstanceId, sectionKey, itemId, userId,
                req.getOutcome(), req.getNotes(), req.getArtifactType(), req.getArtifactId());
        return ApiResponse.success(null);
    }

    // ── Request / Response DTOs ───────────────────────────────────

    @Data
    static class SectionAssignRequest {
        @NotEmpty private List<Long> assigneeUserIds;
        private String notes;
    }

    @Data
    static class ItemRegistrationRequest {
        private String itemRefType;
        private Long   itemRefId;
        private String label;
    }

    @Data
    static class ItemAssignRequest {
        @NotEmpty private List<Long> itemIds;
        @NotNull  private Long       assignedToUserId;
    }

    @Data
    static class ItemCompletionRequest {
        private String outcome;
        private String notes;
        private String artifactType;
        private Long   artifactId;
    }

    record SectionAssignmentResponse(
            Long id, Long taskInstanceId, String sectionKey,
            Long assignedToUserId, Long subTaskInstanceId, String status) {}

    record TaskSectionItemResponse(
            Long id, String sectionKey, String itemRefType,
            Long itemRefId, String itemLabel, String status) {}
}