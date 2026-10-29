package com.kashi.grc.module.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.module.service.ModuleGuideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Module guide — "How this module works", for every module.
 *
 *   GET  /v1/module-guide/{entityType}        lifecycle, actions and who may do them, guide text
 *   GET  /v1/module-guide/{entityType}/demo   walkthrough video source + chapters
 *
 * Platform Admin (Guides & demos):
 *   GET  /v1/module-guide/{entityType}/settings
 *   POST /v1/module-guide/{entityType}/demo/upload-url
 *   PUT  /v1/module-guide/{entityType}
 */
@RestController
@RequestMapping("/v1/module-guide")
@Tag(name = "Module guide", description = "How each module works, with its walkthrough video")
@RequiredArgsConstructor
public class ModuleGuideController {

    private final ModuleGuideService guideService;

    @GetMapping("/{entityType}")
    @Operation(summary = "How a module works — lifecycle, actions and roles, guide text")
    public ResponseEntity<ApiResponse<Map<String, Object>>> guide(@PathVariable String entityType) {
        return ResponseEntity.ok(ApiResponse.success(guideService.guide(entityType)));
    }

    @GetMapping("/{entityType}/demo")
    @Operation(summary = "Module walkthrough video and chapters")
    public ResponseEntity<ApiResponse<Map<String, Object>>> demo(@PathVariable String entityType) {
        return ResponseEntity.ok(ApiResponse.success(guideService.demo(entityType)));
    }

    @GetMapping("/{entityType}/settings")
    @Operation(summary = "Platform Admin: the module guide as stored")
    public ResponseEntity<ApiResponse<Map<String, Object>>> settings(@PathVariable String entityType) {
        return ResponseEntity.ok(ApiResponse.success(guideService.settings(entityType)));
    }

    @PostMapping("/{entityType}/demo/upload-url")
    @Operation(summary = "Platform Admin: presigned PUT URL for a module walkthrough video")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadUrl(
            @PathVariable String entityType, @RequestBody Map<String, Object> body) {
        Long size = body.get("fileSizeBytes") instanceof Number n ? n.longValue() : null;
        return ResponseEntity.ok(ApiResponse.success(guideService.demoUploadUrl(entityType,
                body.get("fileName") == null ? null : String.valueOf(body.get("fileName")),
                body.get("mimeType") == null ? null : String.valueOf(body.get("mimeType")), size)));
    }

    @PutMapping("/{entityType}")
    @Operation(summary = "Platform Admin: save the module guide and video")
    public ResponseEntity<ApiResponse<Map<String, Object>>> save(
            @PathVariable String entityType, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(guideService.save(entityType, body)));
    }
}
