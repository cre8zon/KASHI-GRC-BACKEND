package com.kashi.grc.collab.controller;

import com.kashi.grc.collab.service.CollabRequestService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Collaboration requests — phase 3.
 *
 *   GET   /v1/collab/workspaces/{id}/requests                       { canRaise, requests }
 *   POST  /v1/collab/workspaces/{id}/requests                       raise one (collab:request:raise)
 *   POST  /v1/collab/workspaces/{id}/requests/bulk                  { rows: [...] } — all or none
 *   PATCH /v1/collab/workspaces/{id}/requests/{rid}                 edit (requester / manager)
 *   POST  /v1/collab/workspaces/{id}/requests/{rid}/answer          { note } — the assignee
 *   POST  /v1/collab/workspaces/{id}/requests/{rid}/decision        { decision: accept|reopen|withdraw, note }
 *
 * Rules are in CollabRequestService.
 */
@RestController
@RequestMapping("/v1/collab/workspaces/{id}/requests")
@RequiredArgsConstructor
@Tag(name = "Collaboration requests", description = "Requests between the client and the firm")
public class CollabRequestController {

    private final CollabRequestService service;

    @GetMapping
    @Operation(summary = "Requests in a workspace the caller may see")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.list(id)));
    }

    @PostMapping
    @Operation(summary = "Raise a request")
    public ResponseEntity<ApiResponse<Map<String, Object>>> raise(@PathVariable Long id,
                                                                  @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.raise(id, body)));
    }

    @PostMapping("/bulk")
    @Operation(summary = "Raise many requests at once")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulk(@PathVariable Long id,
                                                                 @RequestBody Map<String, Object> body) {
        Object rows = body.get("rows");
        return ResponseEntity.ok(ApiResponse.success(service.raiseMany(id,
                rows instanceof List<?> l ? (List<Map<String, Object>>) l : null)));
    }

    @PatchMapping("/{rid}")
    @Operation(summary = "Edit a request")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable Long id, @PathVariable Long rid,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, rid, body)));
    }

    @PostMapping("/{rid}/answer")
    @Operation(summary = "Answer a request")
    public ResponseEntity<ApiResponse<Map<String, Object>>> answer(@PathVariable Long id, @PathVariable Long rid,
                                                                   @RequestBody Map<String, Object> body) {
        Object note = body.get("note");
        return ResponseEntity.ok(ApiResponse.success(service.answer(id, rid, note == null ? null : note.toString())));
    }

    @PostMapping("/{rid}/decision")
    @Operation(summary = "Accept, send back or withdraw a request")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable Long id, @PathVariable Long rid,
                                                                   @RequestBody Map<String, Object> body) {
        Object d = body.get("decision"), note = body.get("note");
        return ResponseEntity.ok(ApiResponse.success(service.decide(id, rid,
                d == null ? null : d.toString(), note == null ? null : note.toString())));
    }
}
