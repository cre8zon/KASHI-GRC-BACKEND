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
 *   GET    /v1/chat/presence                            { online: [userId], lastSeen: { userId: time } } — live: "presence" pushes
 *   GET    /v1/chat/conversations                       mine, newest activity first, with unread counts
 *   GET    /v1/chat/conversations/browse                public channels I am not in
 *   POST   /v1/chat/conversations                       { kind: DIRECT|GROUP|CHANNEL, ... }
 *   GET    /v1/chat/conversations/{id}
 *   PATCH  /v1/chat/conversations/{id}                  { name, description, visibility, archived, muted }
 *   POST   /v1/chat/conversations/{id}/join             public channels
 *   POST   /v1/chat/conversations/{id}/members          { userIds }
 *   DELETE /v1/chat/conversations/{id}/members/{userId} remove, or leave (yourself)
 *   GET    /v1/chat/conversations/{id}/messages?before=&limit=
 *   POST   /v1/chat/conversations/{id}/messages         { body, mentions: [userId], replyToId?, attachmentIds?: [documentId] }
 *   GET    /v1/chat/conversations/{id}/members          everyone in it (details panel)
 *   GET    /v1/chat/conversations/{id}/shared?type=media|files|links&before=   what was shared (details panel)
 *   GET    /v1/chat/conversations/{id}/pins             pinned messages
 *   GET    /v1/chat/conversations/{id}/search?q=        text search, newest first
 *   POST   /v1/chat/conversations/{id}/typing           "I am typing" (pushed to the others)
 *   POST   /v1/chat/conversations/{id}/read             { messageId? }
 *   PATCH  /v1/chat/messages/{id}                       { body } — your own
 *   DELETE /v1/chat/messages/{id}                       yours, or as channel owner
 *   POST   /v1/chat/messages/{id}/reactions             { emoji } — toggles mine
 *   POST   /v1/chat/messages/{id}/pin                   { pinned: true|false }
 *
 * Files: upload with the document API to entityType CHAT_CONVERSATION and the
 * conversation id, then send the document ids as attachmentIds.
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

    /**
     * "My client has this message." Sets the second tick.
     *
     * Deliberately the same shape as /read, and deliberately separate from it:
     * the caller is the recipient's app-wide chat socket listener, which fires
     * the moment a push lands in any tab, long before anyone has read anything.
     * Idempotent and advance-only, so a reconnect replaying the same id costs a
     * SELECT and nothing else.
     */
    @PostMapping("/conversations/{id}/delivered")
    @Operation(summary = "Mark as delivered to my client")
    public ResponseEntity<ApiResponse<Void>> delivered(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Object m = body == null ? null : body.get("messageId");
        service.delivered(id, m == null ? null : Long.valueOf(m.toString()));
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * Who had this message, and when. The info panel.
     *
     * Per message rather than per conversation: the state is the same
     * comparison the tick makes, but the TIMES are specific to this message and
     * come from the advance log, so they are only worth fetching when somebody
     * actually opens the panel. The ticks themselves need no request at all.
     */
    @GetMapping("/messages/{id}/receipts")
    @Operation(summary = "Who this message reached, and when")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> messageReceipts(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.messageReceipts(id)));
    }

    @PatchMapping("/messages/{id}")
    @Operation(summary = "Edit my message")
    public ResponseEntity<ApiResponse<Map<String, Object>>> edit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.edit(id, body)));
    }

    @GetMapping("/presence")
    @Operation(summary = "Who in my organisation is online, and when the others were last seen")
    public ResponseEntity<ApiResponse<Map<String, Object>>> presence() {
        return ResponseEntity.ok(ApiResponse.success(service.presence()));
    }

    @GetMapping("/conversations/{id}/members")
    @Operation(summary = "Everyone in a conversation")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> members(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.members(id)));
    }

    @GetMapping("/conversations/{id}/shared")
    @Operation(summary = "Images, files or links shared in a conversation, newest first")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shared(@PathVariable Long id,
                                                                   @RequestParam(value = "type", defaultValue = "media") String type,
                                                                   @RequestParam(value = "before", required = false) Long before) {
        return ResponseEntity.ok(ApiResponse.success(service.shared(id, type, before)));
    }

    @GetMapping("/conversations/{id}/pins")
    @Operation(summary = "Pinned messages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pins(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.pins(id)));
    }

    @GetMapping("/conversations/{id}/search")
    @Operation(summary = "Search this conversation's messages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(@PathVariable Long id, @RequestParam("q") String q) {
        return ResponseEntity.ok(ApiResponse.success(service.search(id, q)));
    }

    @PostMapping("/conversations/{id}/typing")
    @Operation(summary = "Tell the others I am typing")
    public ResponseEntity<ApiResponse<Void>> typing(@PathVariable Long id) {
        service.typing(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/messages/{id}/reactions")
    @Operation(summary = "Add or remove my reaction")
    public ResponseEntity<ApiResponse<Map<String, Object>>> react(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.react(id, body)));
    }

    @PostMapping("/messages/{id}/pin")
    @Operation(summary = "Pin or unpin a message")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pin(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.pin(id, Boolean.parseBoolean(String.valueOf(body.get("pinned"))))));
    }

    @DeleteMapping("/messages/{id}")
    @Operation(summary = "Delete a message")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}