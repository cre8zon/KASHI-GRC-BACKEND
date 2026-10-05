package com.kashi.grc.chat.controller;

import com.kashi.grc.chat.service.ChatService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal chat — the organisation's own staff.
 *
 *   GET    /v1/chat/me                                  { userId, canUse, pushTopic }
 *   GET    /v1/chat/unread                              { total } — sidebar badge
 *   GET    /v1/chat/people                              staff you can message
 *   GET    /v1/chat/conversations                       mine, newest activity first, with unread counts
 *   GET    /v1/chat/conversations/browse                public channels I am not in
 *   POST   /v1/chat/conversations                       { kind: DIRECT|GROUP|CHANNEL, ... }
 *   GET    /v1/chat/conversations/{id}
 *   PATCH  /v1/chat/conversations/{id}                  { name, description, visibility, archived, muted }
 *   POST   /v1/chat/conversations/{id}/join             public channels
 *   POST   /v1/chat/conversations/{id}/members          { userIds }
 *   DELETE /v1/chat/conversations/{id}/members/{userId} remove, or leave (yourself)
 *   GET    /v1/chat/conversations/{id}/messages?before=&limit=
 *   POST   /v1/chat/conversations/{id}/messages         { body, mentions: [userId] }
 *   POST   /v1/chat/conversations/{id}/read             { messageId? }
 *   PATCH  /v1/chat/messages/{id}                       { body } — your own
 *   DELETE /v1/chat/messages/{id}                       yours, or as channel owner
 *
 * Rules are in ChatService.
 */
@RestController
@RequestMapping("/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Internal direct messages, groups and channels")
public class ChatController {

    private final ChatService service;

    @GetMapping("/me")
    @Operation(summary = "Whether I can use chat, and my live-update topic")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me() {
        return ResponseEntity.ok(ApiResponse.success(service.me()));
    }

    @GetMapping("/unread")
    @Operation(summary = "Unread messages in total")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unread() {
        return ResponseEntity.ok(ApiResponse.success(service.unread()));
    }

    @GetMapping("/people")
    @Operation(summary = "Staff I can message")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> people() {
        return ResponseEntity.ok(ApiResponse.success(service.people()));
    }

    @GetMapping("/conversations")
    @Operation(summary = "My conversations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> conversations() {
        return ResponseEntity.ok(ApiResponse.success(service.conversations()));
    }

    @GetMapping("/conversations/browse")
    @Operation(summary = "Public channels I can join")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> browse() {
        return ResponseEntity.ok(ApiResponse.success(service.browse()));
    }

    @PostMapping("/conversations")
    @Operation(summary = "Open a direct conversation, or create a group or channel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.create(body)));
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "A conversation and its members")
    public ResponseEntity<ApiResponse<Map<String, Object>>> conversation(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.conversation(id)));
    }

    @PatchMapping("/conversations/{id}")
    @Operation(summary = "Rename, describe, archive or mute a conversation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, body)));
    }

    @PostMapping("/conversations/{id}/join")
    @Operation(summary = "Join a public channel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> join(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.join(id)));
    }

    @PostMapping("/conversations/{id}/members")
    @Operation(summary = "Add people")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addMembers(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.addMembers(id, body.get("userIds"))));
    }

    @DeleteMapping("/conversations/{id}/members/{userId}")
    @Operation(summary = "Remove someone, or leave")
    public ResponseEntity<ApiResponse<Void>> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        service.removeMember(id, userId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "Messages, oldest first, paging back with before=")
    public ResponseEntity<ApiResponse<Map<String, Object>>> messages(@PathVariable Long id,
                                                                     @RequestParam(required = false) Long before,
                                                                     @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(service.messages(id, before, limit)));
    }

    @PostMapping("/conversations/{id}/messages")
    @Operation(summary = "Send a message")
    public ResponseEntity<ApiResponse<Map<String, Object>>> send(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.send(id, body)));
    }

    @PostMapping("/conversations/{id}/read")
    @Operation(summary = "Mark as read")
    public ResponseEntity<ApiResponse<Void>> read(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Object m = body == null ? null : body.get("messageId");
        service.read(id, m == null ? null : Long.valueOf(m.toString()));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PatchMapping("/messages/{id}")
    @Operation(summary = "Edit my message")
    public ResponseEntity<ApiResponse<Map<String, Object>>> edit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.edit(id, body)));
    }

    @DeleteMapping("/messages/{id}")
    @Operation(summary = "Delete a message")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
