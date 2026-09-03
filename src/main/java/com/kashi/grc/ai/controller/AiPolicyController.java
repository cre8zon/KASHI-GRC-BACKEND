package com.kashi.grc.ai.controller;

import com.kashi.grc.ai.policy.PolicyAiDtos.*;
import com.kashi.grc.ai.policy.PolicyAiService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AI actions on policies.
 *
 * Absolute paths with no class-level @RequestMapping, matching
 * AuditPolicyController's convention exactly.
 *
 * ── NOT ONE OF THESE ENDPOINTS WRITES A POLICY ───────────────────────────────
 * Every response is a suggestion the UI renders for a human to accept, edit or
 * reject. The write still goes through AuditPolicyController and the existing
 * DRAFT -> UNDER_REVIEW -> APPROVED lifecycle, because that lifecycle is what
 * produces the audit evidence an assessor will ask for.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AiPolicyController {

    private final PolicyAiService policyAiService;
    private final UtilityService  utilityService;

    /**
     * Suggest the metadata for a policy that does not exist yet.
     *
     * The CREATE path's first step: one sentence of intent in, title /
     * description / frameworks / control mappings / review cadence out. Single
     * cheap call, because the user is sitting in front of a form.
     *
     * Every field is a suggestion the UI must let them edit before the policy is
     * created. This endpoint creates nothing.
     */
    @PostMapping("/v1/ai/policies/suggest-metadata")
    public ApiResponse<MetadataResponse> suggestMetadata(@Valid @RequestBody MetadataRequest request) {
        User user = utilityService.getLoggedInDataContext();
        MetadataResponse response = policyAiService.suggestMetadata(request, user.getTenantId(), user.getId());
        return (response.getWarnings() != null && !response.getWarnings().isEmpty())
                ? ApiResponse.warning(response)
                : ApiResponse.success(response);
    }

    /** Full draft. The expensive one — a multi-step pipeline, several seconds. */
    @PostMapping("/v1/ai/policies/draft")
    public ApiResponse<DraftResponse> draft(@Valid @RequestBody DraftRequest request) {
        User user = utilityService.getLoggedInDataContext();
        log.info("[AI-POLICY] draft requested | title='{}' controls={} tenantId={}",
                request.getTitle(),
                request.getControlCodes() == null ? 0 : request.getControlCodes().size(),
                user.getTenantId());

        DraftResponse response = policyAiService.draft(request, user.getTenantId(), user.getId());

        // WARNING rather than SUCCESS when references were dropped or context was
        // thin — the UI shows a banner instead of presenting the draft as clean.
        return (response.getWarnings() != null && !response.getWarnings().isEmpty())
                ? ApiResponse.warning(response)
                : ApiResponse.success(response);
    }

    @PostMapping("/v1/ai/policies/rewrite")
    public ApiResponse<RewriteResponse> rewrite(@Valid @RequestBody RewriteRequest request) {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(policyAiService.rewriteSection(request, user.getTenantId(), user.getId()));
    }

    /**
     * Streaming rewrite.
     *
     * ── WHY STREAM THIS ONE AND NOTHING ELSE ─────────────────────────────────
     * The user has text selected and is watching the spot where it will change.
     * Six seconds of spinner feels broken; six seconds of text appearing feels
     * fast. For draft() the opposite holds — the pipeline validates and repairs
     * after generation, so streaming would show text that a later step then
     * rewrites, which reads as the system changing its mind.
     *
     * The timeout is generous because SSE is cheap to hold open and a truncated
     * rewrite is worse than a slow one.
     */
    @PostMapping(value = "/v1/ai/policies/rewrite/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter rewriteStream(@Valid @RequestBody RewriteRequest request) {
        User user = utilityService.getLoggedInDataContext();
        SseEmitter emitter = new SseEmitter(180_000L);

        // Resolve the user on the request thread — the async thread has no
        // SecurityContext, and UtilityService reads from the ThreadLocal.
        Long tenantId = user.getTenantId();
        Long userId   = user.getId();

        CompletableFuture.runAsync(() -> {
            try {
                policyAiService.rewriteSectionStreaming(request, tenantId, userId, token -> {
                    try { emitter.send(SseEmitter.event().name("token").data(token)); }
                    catch (IOException e) { throw new RuntimeException("client disconnected", e); }
                });
                emitter.send(SseEmitter.event().name("done").data("{}"));
                emitter.complete();
            } catch (Exception e) {
                log.warn("[AI-POLICY] rewrite stream ended early: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            Map.of("message", e.getMessage() == null ? "Generation failed" : e.getMessage())));
                } catch (IOException ignored) { /* client already gone */ }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /** Control mapping suggestions. Every reference is validated against the catalogue. */
    @PostMapping("/v1/ai/policies/suggest-mappings")
    public ApiResponse<MappingResponse> suggestMappings(@Valid @RequestBody MappingRequest request) {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(policyAiService.suggestMappings(request, user.getTenantId(), user.getId()));
    }

    @PostMapping("/v1/ai/policies/gap-analysis")
    public ApiResponse<GapResponse> gapAnalysis(@Valid @RequestBody GapRequest request) {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(policyAiService.analyseGaps(request, user.getTenantId(), user.getId()));
    }

    @PostMapping("/v1/ai/policies/explain")
    public ApiResponse<ExplainResponse> explain(@Valid @RequestBody ExplainRequest request) {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(policyAiService.explainClause(request, user.getTenantId(), user.getId()));
    }
}