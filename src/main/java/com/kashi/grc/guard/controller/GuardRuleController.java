package com.kashi.grc.guard.controller;

import com.kashi.grc.actionitem.domain.ActionItemBlueprint;
import com.kashi.grc.actionitem.repository.ActionItemBlueprintRepository;
import com.kashi.grc.assessment.repository.AssessmentQuestionRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.guard.domain.GuardRule;
import com.kashi.grc.guard.dto.GuardRuleRequest;
import com.kashi.grc.guard.dto.GuardRuleResponse;
import com.kashi.grc.guard.repository.GuardRuleRepository;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * KashiGuard — Rule Management API.
 *
 * Rules are now tag-based: one rule covers all questions sharing a tag,
 * across all templates and modules. No question ID needed.
 *
 * Endpoints:
 *   GET    /v1/guard/rules               — list all rules visible to this tenant
 *   GET    /v1/guard/rules/tag/{tag}     — rules for a specific question tag
 *   POST   /v1/guard/rules               — create a new rule
 *   PUT    /v1/guard/rules/{id}          — update a rule
 *   PATCH  /v1/guard/rules/{id}/toggle   — toggle active/inactive
 *   DELETE /v1/guard/rules/{id}          — delete a rule
 *
 * Access: ORG_ADMIN or SYSTEM (platform admin) only.
 * Tenants manage their own rules; global rules (tenantId=null) are system-admin only.
 */
@Slf4j
@RestController
@Tag(name = "KashiGuard Rules", description = "Manage auto-finding trigger rules")
@RequiredArgsConstructor
public class GuardRuleController {

    private final GuardRuleRepository           guardRuleRepository;
    private final ActionItemBlueprintRepository blueprintRepository;
    // FIX 1: questionRepository was missing — needed for countByQuestionTag enrichment
    private final AssessmentQuestionRepository  questionRepository;
    private final UtilityService                utilityService;

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping("/v1/guard/rules")
    @Operation(summary = "List all guard rules visible to this tenant (global + own)")
    public ResponseEntity<ApiResponse<List<GuardRuleResponse>>> list() {
        User user = utilityService.getLoggedInDataContext();
        List<GuardRule> rules = guardRuleRepository.findAll().stream()
                .filter(r -> r.getTenantId() == null
                        || r.getTenantId().equals(user.getTenantId()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(toResponseList(rules)));
    }

    /**
     * GET /v1/guard/rules/tag/{tag}
     * All active rules for a specific question tag — useful in the question
     * library UI and admin panel to see which rules apply to a category.
     */
    @GetMapping("/v1/guard/rules/tag/{tag}")
    @Operation(summary = "Get all active guard rules for a question tag")
    public ResponseEntity<ApiResponse<List<GuardRuleResponse>>> forTag(
            @PathVariable String tag) {
        User user = utilityService.getLoggedInDataContext();
        List<GuardRule> rules = guardRuleRepository
                .findActiveRulesForTag(tag, user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(toResponseList(rules)));
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping("/v1/guard/rules")
    @Operation(summary = "Create a guard rule for a question tag")
    public ResponseEntity<ApiResponse<GuardRuleResponse>> create(
            @Valid @RequestBody GuardRuleRequest req) {
        User user = utilityService.getLoggedInDataContext();

        blueprintRepository.findByBlueprintCode(req.getBlueprintCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ActionItemBlueprint", "blueprintCode", req.getBlueprintCode()));

        GuardRule rule = GuardRule.builder()
                .tenantId(user.getTenantId())
                .questionTag(req.getQuestionTag())
                .conditionType(req.getConditionType())
                .conditionValue(req.getConditionValue())
                .blueprintCode(req.getBlueprintCode())
                .assignedRole(req.getAssignedRole())
                .priorityOverride(req.getPriorityOverride())
                .ruleDescription(req.getRuleDescription())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();

        guardRuleRepository.save(rule);
        log.info("[KASHI-GUARD] Rule created id={} tag='{}' condition={}/{} blueprint={}",
                rule.getId(), rule.getQuestionTag(),
                rule.getConditionType(), rule.getConditionValue(), rule.getBlueprintCode());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toResponse(rule)));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/v1/guard/rules/{id}")
    @Operation(summary = "Update a guard rule")
    public ResponseEntity<ApiResponse<GuardRuleResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody GuardRuleRequest req) {
        User user = utilityService.getLoggedInDataContext();
        GuardRule rule = findAndAuthorize(id, user);

        if (!rule.getBlueprintCode().equals(req.getBlueprintCode())) {
            blueprintRepository.findByBlueprintCode(req.getBlueprintCode())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ActionItemBlueprint", "blueprintCode", req.getBlueprintCode()));
        }

        rule.setQuestionTag(req.getQuestionTag());
        rule.setConditionType(req.getConditionType());
        rule.setConditionValue(req.getConditionValue());
        rule.setBlueprintCode(req.getBlueprintCode());
        rule.setAssignedRole(req.getAssignedRole());
        rule.setPriorityOverride(req.getPriorityOverride());
        rule.setRuleDescription(req.getRuleDescription());
        if (req.getIsActive() != null) rule.setIsActive(req.getIsActive());

        guardRuleRepository.save(rule);
        log.info("[KASHI-GUARD] Rule updated id={} tag='{}'", id, rule.getQuestionTag());

        return ResponseEntity.ok(ApiResponse.success(toResponse(rule)));
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    @PatchMapping("/v1/guard/rules/{id}/toggle")
    @Operation(summary = "Toggle a guard rule active/inactive")
    public ResponseEntity<ApiResponse<GuardRuleResponse>> toggle(@PathVariable Long id) {
        User user = utilityService.getLoggedInDataContext();
        GuardRule rule = findAndAuthorize(id, user);

        rule.setIsActive(!Boolean.TRUE.equals(rule.getIsActive()));
        guardRuleRepository.save(rule);
        log.info("[KASHI-GUARD] Rule {} toggled → isActive={}", id, rule.getIsActive());

        return ResponseEntity.ok(ApiResponse.success(toResponse(rule)));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/v1/guard/rules/{id}")
    @Operation(summary = "Delete a guard rule — prefer toggle (disable) for auditability")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        User user = utilityService.getLoggedInDataContext();
        GuardRule rule = findAndAuthorize(id, user);

        guardRuleRepository.delete(rule);
        log.info("[KASHI-GUARD] Rule deleted id={}", id);

        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GuardRule findAndAuthorize(Long id, User user) {
        GuardRule rule = guardRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GuardRule", id));

        if (rule.getTenantId() == null && user.getTenantId() != null) {
            throw new BusinessException("FORBIDDEN",
                    "Global rules can only be managed by platform administrators",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        if (rule.getTenantId() != null
                && !rule.getTenantId().equals(user.getTenantId())) {
            throw new BusinessException("FORBIDDEN",
                    "You do not have permission to manage this rule",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return rule;
    }

    private List<GuardRuleResponse> toResponseList(List<GuardRule> rules) {
        return rules.stream().map(this::toResponse).toList();
    }

    private GuardRuleResponse toResponse(GuardRule rule) {
        // FIX 2: was rule.questionCount() — that's a method call on the entity which
        // doesn't exist. questionCount is a local variable computed from the repository.
        int questionCount = (int) questionRepository.countByQuestionTag(rule.getQuestionTag());

        String blueprintTitle = null;
        try {
            blueprintTitle = blueprintRepository
                    .findByBlueprintCode(rule.getBlueprintCode())
                    .map(ActionItemBlueprint::getTitleTemplate)
                    .orElse(null);
        } catch (Exception ignored) {}

        return GuardRuleResponse.builder()
                .id(rule.getId())
                .tenantId(rule.getTenantId())
                .isGlobal(rule.getTenantId() == null)
                .questionTag(rule.getQuestionTag())
                .questionCount(questionCount)          // FIX 3: was rule.questionCount()
                .conditionType(rule.getConditionType())
                .conditionValue(rule.getConditionValue())
                .blueprintCode(rule.getBlueprintCode())
                .blueprintTitle(blueprintTitle)
                .assignedRole(rule.getAssignedRole())
                .priorityOverride(rule.getPriorityOverride())
                .ruleDescription(rule.getRuleDescription())
                .isActive(rule.getIsActive())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }
}