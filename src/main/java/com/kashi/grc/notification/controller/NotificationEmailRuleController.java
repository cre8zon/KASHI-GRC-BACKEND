package com.kashi.grc.notification.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.domain.NotificationEmailRule;
import com.kashi.grc.notification.repository.NotificationEmailRuleRepository;
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

/**
 * Admin CRUD for notification → email rules.
 *
 * These rows control what NotificationEmailConsumer does per eventKey:
 *   no rows              → raw fallback email (default: everything emails)
 *   rows with template   → one email per template per recipient
 *   row with suppress    → no email for this event at all
 *
 * The review workflow after go-live: watch notification_dispatch_log,
 * suppress the noisy eventKeys, add curated templates for the keepers.
 */
@RestController
@RequestMapping("/v1/admin/notification-email-rules")
@Tag(name = "Notification Email Rules",
        description = "Map notification eventKeys to email templates (or suppress)")
@RequiredArgsConstructor
public class NotificationEmailRuleController {

    private final NotificationEmailRuleRepository ruleRepository;

    @GetMapping
    @Operation(summary = "List rules — optionally filtered by eventKey")
    public ResponseEntity<ApiResponse<List<NotificationEmailRule>>> list(
            @RequestParam(required = false) String eventKey) {
        List<NotificationEmailRule> rules = eventKey != null
                ? ruleRepository.findByEventKeyOrderByTenantIdAsc(eventKey)
                : ruleRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(rules));
    }

    @PostMapping
    @Operation(summary = "Create a rule (template mapping or suppression)")
    public ResponseEntity<ApiResponse<NotificationEmailRule>> create(
            @Valid @RequestBody RuleRequest req) {
        NotificationEmailRule rule = NotificationEmailRule.builder()
                .eventKey(req.getEventKey())
                .tenantId(req.getTenantId())
                .templateName(req.getTemplateName())
                .audience(req.getAudience() != null
                        ? req.getAudience() : NotificationEmailRule.Audience.RECIPIENT)
                .suppressEmail(Boolean.TRUE.equals(req.getSuppressEmail()))
                .isActive(true)
                .build();
        ruleRepository.save(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(rule));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a rule")
    public ResponseEntity<ApiResponse<NotificationEmailRule>> update(
            @PathVariable Long id, @Valid @RequestBody RuleRequest req) {
        NotificationEmailRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationEmailRule", id));
        rule.setEventKey(req.getEventKey());
        rule.setTenantId(req.getTenantId());
        rule.setTemplateName(req.getTemplateName());
        if (req.getAudience() != null) rule.setAudience(req.getAudience());
        rule.setSuppressEmail(Boolean.TRUE.equals(req.getSuppressEmail()));
        if (req.getIsActive() != null) rule.setActive(req.getIsActive());
        ruleRepository.save(rule);
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a rule (hard delete — rules are pure config)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!ruleRepository.existsById(id)) {
            throw new ResourceNotFoundException("NotificationEmailRule", id);
        }
        ruleRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Data
    public static class RuleRequest {
        @NotBlank private String  eventKey;
        private Long    tenantId;       // null = global
        private String  templateName;   // null + suppressEmail=true = mute event
        private NotificationEmailRule.Audience audience;  // default RECIPIENT
        private Boolean suppressEmail;
        private Boolean isActive;
    }
}
