package com.kashi.grc.collab.controller;

import com.kashi.grc.collab.service.CollabWorkspaceService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Collaboration workspaces — phase 1 (workspaces, members, programmes).
 *
 *   GET    /v1/collab/workspaces                                  visible workspaces
 *   GET    /v1/collab/workspaces/options                          firms the caller can open one with
 *   POST   /v1/collab/workspaces                                  { name, description, firmTenantId? }
 *   GET    /v1/collab/workspaces/{id}                             overview: members, programmes
 *   PATCH  /v1/collab/workspaces/{id}                             { name?, description?, status? }
 *   GET    /v1/collab/workspaces/{id}/eligible-members            people who can be added
 *   POST   /v1/collab/workspaces/{id}/members                     { userId, role }
 *   PATCH  /v1/collab/workspaces/{id}/members/{memberId}          { role }
 *   DELETE /v1/collab/workspaces/{id}/members/{memberId}
 *   POST   /v1/collab/workspaces/{id}/programmes                  { name, description, plannedStart, plannedEnd }
 *   PATCH  /v1/collab/workspaces/{id}/programmes/{pid}            same fields + status
 *   POST   /v1/collab/workspaces/{id}/programmes/{pid}/members    { userId }
 *   DELETE /v1/collab/workspaces/{id}/programmes/{pid}            delete (owners); its work moves to Workspace-wide
 *   DELETE /v1/collab/workspaces/{id}/programmes/{pid}/members/{userId}
 *
 * Every rule (who sees, who manages, who creates) is in CollabAccessService.
 * A workspace the caller cannot see answers 404, never 403.
 */
@RestController
@RequestMapping("/v1/collab")
@RequiredArgsConstructor
@Tag(name = "Collaboration", description = "Workspaces shared between an organisation and its audit firm")
public class CollabController {

    private final CollabWorkspaceService service;

    @GetMapping("/workspaces")
    @Operation(summary = "Workspaces the caller can see in the active organisation")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list()));
    }

    @GetMapping("/workspaces/options")
    @Operation(summary = "Whether the caller can create a workspace, and with which firms")
    public ResponseEntity<ApiResponse<Map<String, Object>>> options() {
        return ResponseEntity.ok(ApiResponse.success(service.creationOptions()));
    }

    @PostMapping("/workspaces")
    @Operation(summary = "Create a workspace (with an admitted firm, or internal)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        Long firm = body.get("firmTenantId") == null || body.get("firmTenantId").toString().isBlank()
                ? null : Long.parseLong(body.get("firmTenantId").toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(
                str(body.get("name")), str(body.get("description")), firm)));
    }

    @GetMapping("/workspaces/{id}")
    @Operation(summary = "Workspace overview: members and the programmes the caller can see")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.overview(id)));
    }

    @PatchMapping("/workspaces/{id}")
    @Operation(summary = "Rename, describe or archive a workspace (owners)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable Long id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, body)));
    }

    @GetMapping("/workspaces/{id}/eligible-members")
    @Operation(summary = "People who can be added: the organisation's staff and this workspace's firm")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> eligible(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.eligibleMembers(id)));
    }

    @PostMapping("/workspaces/{id}/members")
    @Operation(summary = "Add a member (owners)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addMember(@PathVariable Long id,
                                                                      @RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") == null ? null : Long.parseLong(body.get("userId").toString());
        return ResponseEntity.ok(ApiResponse.success(service.addMember(id, userId, str(body.get("role")))));
    }

    @PatchMapping("/workspaces/{id}/members/{memberId}")
    @Operation(summary = "Change a member's workspace role (owners)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> changeRole(@PathVariable Long id,
                                                                       @PathVariable Long memberId,
                                                                       @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.changeRole(id, memberId, str(body.get("role")))));
    }

    @DeleteMapping("/workspaces/{id}/members/{memberId}")
    @Operation(summary = "Remove a member (owners)")
    public ResponseEntity<ApiResponse<Void>> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        service.removeMember(id, memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/workspaces/{id}/programmes")
    @Operation(summary = "Create a programme (owners)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createProgramme(@PathVariable Long id,
                                                                            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.createProgramme(id, body)));
    }

    @PatchMapping("/workspaces/{id}/programmes/{pid}")
    @Operation(summary = "Update a programme (owners)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProgramme(@PathVariable Long id,
                                                                            @PathVariable Long pid,
                                                                            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.updateProgramme(id, pid, body)));
    }

    @PostMapping("/workspaces/{id}/programmes/{pid}/members")
    @Operation(summary = "Put a workspace member on a programme (owners)")
    public ResponseEntity<ApiResponse<Void>> addProgrammeMember(@PathVariable Long id, @PathVariable Long pid,
                                                                @RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") == null ? null : Long.parseLong(body.get("userId").toString());
        service.addProgrammeMember(id, pid, userId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @DeleteMapping("/workspaces/{id}/programmes/{pid}")
    @Operation(summary = "Delete a programme (owners) — its plan items, meetings and requests move to Workspace-wide")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteProgramme(@PathVariable Long id, @PathVariable Long pid) {
        return ResponseEntity.ok(ApiResponse.success(service.deleteProgramme(id, pid)));
    }

    @DeleteMapping("/workspaces/{id}/programmes/{pid}/members/{userId}")
    @Operation(summary = "Take a member off a programme (owners)")
    public ResponseEntity<ApiResponse<Void>> removeProgrammeMember(@PathVariable Long id, @PathVariable Long pid,
                                                                   @PathVariable Long userId) {
        service.removeProgrammeMember(id, pid, userId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
