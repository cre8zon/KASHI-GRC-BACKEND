package com.kashi.grc.collab.controller;

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
 * A join answer is { url, token, room, title, expiresIn }. Rules are in
 * CollabCallService.
 */
@RestController
@RequestMapping("/v1/collab")
@RequiredArgsConstructor
@Tag(name = "Collaboration calls", description = "In-app video calls and standing rooms")
public class CollabCallController {

    private final CollabCallService service;

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
}
