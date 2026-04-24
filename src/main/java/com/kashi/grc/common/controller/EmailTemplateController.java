package com.kashi.grc.common.controller;

import com.kashi.grc.common.domain.EmailTemplate;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.repository.EmailTemplateRepository;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

@RestController
@RequestMapping("/v1/admin/email-templates")
@Tag(name = "Email Template Management", description = "CRUD for DB-stored email templates with live preview")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateRepository emailTemplateRepository;
    private final DbRepository            dbRepository;
    private final UtilityService          utilityService;

    // ── Create ────────────────────────────────────────────────────
    @PostMapping
    @Operation(summary = "Create a new email template")
    public ResponseEntity<ApiResponse<TemplateResponse>> create(
            @Valid @RequestBody TemplateRequest req) {
        if (emailTemplateRepository.findByName(req.getName()).isPresent()) {
            throw new BusinessException("TEMPLATE_EXISTS",
                    "Template with name '" + req.getName() + "' already exists");
        }
        EmailTemplate t = new EmailTemplate();
        t.setName(req.getName());
        t.setDescription(req.getDescription());
        t.setSubject(req.getSubject());
        t.setContent(req.getContent());
        t.setMimeType(req.getMimeType() != null ? req.getMimeType() : "text/html");
        t.setActive(true);
        emailTemplateRepository.save(t);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toResponse(t)));
    }

    // ── List (paginated) ──────────────────────────────────────────
    @GetMapping
    @Operation(summary = "List all email templates — paginated, searchable")
    public ResponseEntity<ApiResponse<PaginatedResponse<TemplateResponse>>> list(
            @RequestParam Map<String, String> allParams) {
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                EmailTemplate.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(),   // no tenant filter — email templates are global
                (cb, root) -> Map.of(
                        "name",        root.get("name"),
                        "description", root.get("description"),
                        "subject",     root.get("subject")),
                this::toResponse
        )));
    }

    // ── Get by ID ─────────────────────────────────────────────────
    @GetMapping("/{id}")
    @Operation(summary = "Get email template by ID")
    public ResponseEntity<ApiResponse<TemplateResponse>> getById(@PathVariable Long id) {
        EmailTemplate t = emailTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EmailTemplate", id));
        return ResponseEntity.ok(ApiResponse.success(toResponse(t)));
    }

    // ── Get by Name ───────────────────────────────────────────────
    @GetMapping("/by-name/{name}")
    @Operation(summary = "Get email template by name key")
    public ResponseEntity<ApiResponse<TemplateResponse>> getByName(@PathVariable String name) {
        EmailTemplate t = emailTemplateRepository.findByName(name)
                .orElseThrow(() -> new ResourceNotFoundException("EmailTemplate", "name", name));
        return ResponseEntity.ok(ApiResponse.success(toResponse(t)));
    }

    // ── Update ────────────────────────────────────────────────────
    @PutMapping("/{id}")
    @Operation(summary = "Update an email template")
    public ResponseEntity<ApiResponse<TemplateResponse>> update(
            @PathVariable Long id, @RequestBody TemplateRequest req) {
        EmailTemplate t = emailTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EmailTemplate", id));
        if (req.getDescription() != null) t.setDescription(req.getDescription());
        if (req.getSubject()     != null) t.setSubject(req.getSubject());
        if (req.getContent()     != null) t.setContent(req.getContent());
        if (req.getMimeType()    != null) t.setMimeType(req.getMimeType());
        emailTemplateRepository.save(t);
        return ResponseEntity.ok(ApiResponse.success(toResponse(t)));
    }

    // ── Toggle Active ─────────────────────────────────────────────
    @PatchMapping("/{id}/toggle")
    @Operation(summary = "Toggle active/inactive status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toggle(@PathVariable Long id) {
        EmailTemplate t = emailTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EmailTemplate", id));
        t.setActive(!t.isActive());
        emailTemplateRepository.save(t);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("id", t.getId(), "name", t.getName(), "isActive", t.isActive())));
    }

    // ── Preview (merge variables into template) ───────────────────
    @PostMapping("/{id}/preview")
    @Operation(summary = "Preview rendered template with sample variables")
    public ResponseEntity<ApiResponse<PreviewResponse>> preview(
            @PathVariable Long id,
            @RequestBody Map<String, String> variables) {
        EmailTemplate t = emailTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EmailTemplate", id));
        String subject = merge(t.getSubject(), variables);
        String body    = merge(t.getContent(),  variables);
        return ResponseEntity.ok(ApiResponse.success(PreviewResponse.builder()
                .templateId(t.getId()).templateName(t.getName())
                .renderedSubject(subject).renderedBody(body)
                .mimeType(t.getMimeType())
                .detectedVariables(extractVariables(t.getContent() + " " + t.getSubject()))
                .build()));
    }

    // ── Extract variables from template ───────────────────────────
    @GetMapping("/{id}/variables")
    @Operation(summary = "Extract all {{placeholder}} variables from a template")
    public ResponseEntity<ApiResponse<Map<String, Object>>> variables(@PathVariable Long id) {
        EmailTemplate t = emailTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EmailTemplate", id));
        List<String> vars = extractVariables(t.getContent() + " " + t.getSubject());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "templateId", t.getId(),
                "templateName", t.getName(),
                "variables", vars)));
    }

    // ── Delete ────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an email template")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        emailTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EmailTemplate", id));
        emailTemplateRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Helpers ───────────────────────────────────────────────────
    private String merge(String template, Map<String, String> vars) {
        if (template == null || vars == null) return template;
        String result = template;
        for (var e : vars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}",
                    e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private List<String> extractVariables(String text) {
        List<String> vars = new ArrayList<>();
        if (text == null) return vars;
        Matcher m = Pattern.compile("\\{\\{(\\w+)\\}\\}").matcher(text);
        while (m.find()) {
            String v = m.group(1);
            if (!vars.contains(v)) vars.add(v);
        }
        return vars;
    }

    private TemplateResponse toResponse(EmailTemplate t) {
        return TemplateResponse.builder()
                .id(t.getId()).name(t.getName()).description(t.getDescription())
                .subject(t.getSubject()).content(t.getContent())
                .mimeType(t.getMimeType()).isActive(t.isActive())
                .variables(extractVariables(t.getContent() + " " + t.getSubject()))
                .createdAt(t.getCreatedAt()).updatedAt(t.getUpdatedAt())
                .build();
    }

    // ── Inline DTOs ───────────────────────────────────────────────
    @Data
    public static class TemplateRequest {
        @NotBlank private String name;
        @NotBlank private String description;
        @NotBlank private String subject;
        @NotBlank private String content;
        private String mimeType;
    }

    @lombok.Builder
    @Data
    public static class TemplateResponse {
        private Long   id;
        private String name, description, subject, content, mimeType;
        private boolean isActive;
        private List<String> variables;
        private java.time.LocalDateTime createdAt, updatedAt;
    }

    @lombok.Builder
    @Data
    public static class PreviewResponse {
        private Long   templateId;
        private String templateName, renderedSubject, renderedBody, mimeType;
        private List<String> detectedVariables;
    }
}
