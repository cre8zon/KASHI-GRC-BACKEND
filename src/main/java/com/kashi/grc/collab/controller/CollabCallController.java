package com.kashi.grc.collab.controller;

import com.kashi.grc.collab.service.CollabCallLinkService;
import com.kashi.grc.collab.service.CollabCallService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * In-app calls — phase 4 (self-hosted LiveKit).
 *
 *   GET   /v1/collab/calls/options             { enabled, canStart }
 *   POST  /v1/collab/calls                     call now: { workspaceId?, programmeId?, title?, attendeeUserIds }
 *   POST  /v1/collab/meetings/{id}/join        a token for the meeting's call
 *   GET   /v1/collab/rooms                     my standing rooms, with who is in them now
 *   POST  /v1/collab/rooms                     { name, description?, workspaceId?, memberUserIds }
 *   GET   /v1/collab/rooms/{id}                the room page: room, upcoming meetings, past sessions
 *   POST  /v1/collab/rooms/{id}/meetings       schedule in the room: { title?, startsAt, endsAt?, agenda?,
 *                                              repeat: NONE|DAILY|WEEKDAYS|WEEKLY, repeatUntil? }
 *   PATCH /v1/collab/rooms/{id}                { name?, description?, memberUserIds?, archived? }
 *   POST  /v1/collab/rooms/{id}/join           a token for the room (joins the meeting on now,
 *                                              or the running drop-in session, or starts one)
 *
 *
 * Call links and the lobby (CollabCallLinkService):
 *   POST  /v1/collab/calls/link                { meetingId } → the call's link (created on first ask)
 *   PATCH /v1/collab/calls/link                { meetingId, allowGuests?, regenerate? } — whoever runs the call
 *   GET   /v1/collab/calls/lobby?meetingId=    guests waiting to be let in, and guests in the call
 *   POST  /v1/collab/calls/lobby/{id}/{action}?meetingId=  admit | deny | remove (meetingId = the call you are in)
 *   POST  /v1/collab/call-links/{code}/join    opened by a signed-in user: { mode: DIRECT, …join } or { mode: GUEST }
 *   GET   /v1/collab/call-links/{code}         a chat card's preview: { title, active }
 * The guest's own endpoints are in CollabCallPublicController.
 *
 * A join answer is { url, token, room, title, expiresIn }. Rules are in
 * CollabCallService.
 */
@RestController
@RequestMapping("/v1/collab")
@RequiredArgsConstructor
@Tag(name = "Collaboration calls", description = "In-app video calls and standing rooms")
public class CollabCallController {

    private final CollabCallService service;
    private final CollabCallLinkService links;

    @GetMapping("/calls/options")
    @Operation(summary = "Whether in-app calls are available, and whether the caller can start one")
    public ResponseEntity<ApiResponse<Map<String, Object>>> options() {
        return ResponseEntity.ok(ApiResponse.success(service.options()));
    }

    @PostMapping("/calls")
    @Operation(summary = "Start a call now with these people")
    public ResponseEntity<ApiResponse<Map<String, Object>>> callNow(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.callNow(body)));
    }

    @PostMapping("/meetings/{id}/join")
    @Operation(summary = "Join a meeting's in-app call")
    public ResponseEntity<ApiResponse<Map<String, Object>>> joinMeeting(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.joinMeeting(id)));
    }

    @GetMapping("/rooms")
    @Operation(summary = "My standing call rooms")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rooms() {
        return ResponseEntity.ok(ApiResponse.success(service.rooms()));
    }

    @PostMapping("/rooms")
    @Operation(summary = "Create a standing call room")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createRoom(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.createRoom(body)));
    }

    @GetMapping("/rooms/{id}")
    @Operation(summary = "A room with its upcoming meetings and past sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> room(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.room(id)));
    }

    @PostMapping("/rooms/{id}/meetings")
    @Operation(summary = "Schedule a meeting in a room, once or repeating")
    public ResponseEntity<ApiResponse<Map<String, Object>>> schedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.scheduleInRoom(id, body)));
    }

    @PatchMapping("/rooms/{id}")
    @Operation(summary = "Rename a room, change its members or archive it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateRoom(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.updateRoom(id, body)));
    }

    @PostMapping("/rooms/{id}/join")
    @Operation(summary = "Join a standing room")
    public ResponseEntity<ApiResponse<Map<String, Object>>> joinRoom(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.joinRoom(id)));
    }

    // ── call links & lobby ────────────────────────────────────────────────────

    @PostMapping("/calls/link")
    @Operation(summary = "The call's shareable link (created on first ask)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> link(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(links.link(meetingId(body))));
    }

    @PatchMapping("/calls/link")
    @Operation(summary = "Allow or stop guests, or replace the link")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateLink(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(links.updateLink(meetingId(body), body)));
    }

    @GetMapping("/calls/lobby")
    @Operation(summary = "Guests waiting to join this call, and guests in it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lobby(@RequestParam Long meetingId) {
        return ResponseEntity.ok(ApiResponse.success(links.lobby(meetingId)));
    }

    @PostMapping("/calls/lobby/{id}/{action}")
    @Operation(summary = "Admit, turn away or remove a guest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable Long id, @PathVariable String action,
                                                                   @RequestParam(required = false) Long meetingId) {
        return ResponseEntity.ok(ApiResponse.success(links.decide(id, action, meetingId)));
    }

    @PostMapping("/call-links/{code}/join")
    @Operation(summary = "Open a call link while signed in")
    public ResponseEntity<ApiResponse<Map<String, Object>>> openLink(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.success(links.openSignedIn(code)));
    }

    @GetMapping("/call-links/{code}")
    @Operation(summary = "What a call link points at, for a chat card")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.success(links.preview(code)));
    }

    private static Long meetingId(Map<String, Object> body) {
        Object v = body == null ? null : body.get("meetingId");
        try { return Long.parseLong(String.valueOf(v)); }
        catch (NumberFormatException e) {
            throw new com.kashi.grc.common.exception.BusinessException("COLLAB_BAD_ID", "meetingId is required");
        }
    }
}