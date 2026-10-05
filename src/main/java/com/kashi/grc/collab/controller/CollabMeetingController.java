package com.kashi.grc.collab.controller;

import com.kashi.grc.collab.service.CollabMeetingService;
import com.kashi.grc.collab.service.CollabRequestService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collaboration meetings — phase 3.
 *
 *   GET   /v1/collab/workspaces/{id}/meetings                  the workspace's meetings
 *   GET   /v1/collab/meetings?from=&to=                        meetings I organise or attend
 *   GET   /v1/collab/meetings/options                          what I may schedule, and where
 *   GET   /v1/collab/meetings/eligible-attendees?workspaceId=&programmeId=
 *   GET   /v1/collab/meetings/{id}                             the record
 *   POST  /v1/collab/meetings                                  schedule (collab:meeting:manage)
 *   PATCH /v1/collab/meetings/{id}                             edit, minutes, decisions, attendance, cancel
 *   POST  /v1/collab/meetings/{id}/follow-ups                  a follow-up action item
 *   POST  /v1/collab/meetings/{id}/cancel-series               cancel this and later occurrences of a repeating meeting
 *   GET   /v1/collab/my-week/extras                            next 14 days of meetings + my requests
 *
 * Rules are in CollabMeetingService.
 */
@RestController
@RequestMapping("/v1/collab")
@RequiredArgsConstructor
@Tag(name = "Collaboration meetings", description = "Meetings, minutes and follow-ups")
public class CollabMeetingController {

    private final CollabMeetingService service;
    private final CollabRequestService requestService;

    @GetMapping("/workspaces/{id}/meetings")
    @Operation(summary = "Meetings in a workspace the caller may see")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> forWorkspace(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.listForWorkspace(id)));
    }

    @GetMapping("/meetings")
    @Operation(summary = "Meetings I organise or attend")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> mine(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(service.myMeetings(
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay())));
    }

    @GetMapping("/meetings/options")
    @Operation(summary = "Whether the caller may schedule meetings, and in which workspaces")
    public ResponseEntity<ApiResponse<Map<String, Object>>> options() {
        return ResponseEntity.ok(ApiResponse.success(service.options()));
    }

    @GetMapping("/meetings/eligible-attendees")
    @Operation(summary = "People who can be invited")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> eligible(
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Long programmeId) {
        return ResponseEntity.ok(ApiResponse.success(service.eligibleAttendees(workspaceId, programmeId)));
    }

    @GetMapping("/meetings/{id}")
    @Operation(summary = "A meeting and its record")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping("/meetings")
    @Operation(summary = "Schedule a meeting")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.create(body)));
    }

    @PatchMapping("/meetings/{id}")
    @Operation(summary = "Change a meeting or its record")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable Long id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, body)));
    }

    @PostMapping("/meetings/{id}/follow-ups")
    @Operation(summary = "Add a follow-up from the meeting")
    public ResponseEntity<ApiResponse<Map<String, Object>>> followUp(@PathVariable Long id,
                                                                     @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.addFollowUp(id, body)));
    }

    @PostMapping("/meetings/{id}/cancel-series")
    @Operation(summary = "Cancel this and every later occurrence of a repeating meeting")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelSeries(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.cancelSeries(id)));
    }

    @GetMapping("/my-week/extras")
    @Operation(summary = "My meetings in the next 14 days, and requests waiting on me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myWeekExtras() {
        LocalDateTime from = LocalDate.now().atStartOfDay();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meetings", service.myMeetings(from, from.plusDays(15)));
        out.putAll(requestService.mine());
        return ResponseEntity.ok(ApiResponse.success(out));
    }
}
