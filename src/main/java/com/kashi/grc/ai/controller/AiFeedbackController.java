package com.kashi.grc.ai.controller;

import com.kashi.grc.ai.feedback.AiFeedbackService;
import com.kashi.grc.ai.feedback.AiFeedbackService.FeedbackRequest;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Feedback capture. Small surface, disproportionate long-term value.
 *
 * The frontend must call this on EVERY accept, reject, edit and dismiss. A
 * suggestion shown and silently abandoned is a data point you can only collect
 * at the moment it happens — see AiSuggestionFeedback for why none of this can
 * be reconstructed later.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AiFeedbackController {

    private final AiFeedbackService feedbackService;
    private final UtilityService    utilityService;

    @PostMapping("/v1/ai/feedback")
    public ApiResponse<Map<String, Object>> record(@RequestBody FeedbackRequest request) {
        User user = utilityService.getLoggedInDataContext();
        var saved = feedbackService.record(request, user.getTenantId(), user.getId());
        return ApiResponse.success(Map.of("recorded", saved != null,
                "id", saved == null ? -1L : saved.getId()));
    }

    /** One call per mapping panel rather than one per suggested control. */
    @PostMapping("/v1/ai/feedback/batch")
    public ApiResponse<Map<String, Object>> recordBatch(@RequestBody List<FeedbackRequest> requests) {
        User user = utilityService.getLoggedInDataContext();
        int n = feedbackService.recordBatch(requests, user.getTenantId(), user.getId());
        return ApiResponse.success(Map.of("recorded", n, "submitted", requests.size()));
    }

    /** The acceptance dashboard. This is the number to put on an investor slide. */
    @GetMapping("/v1/ai/feedback/acceptance")
    public ApiResponse<Map<String, Object>> acceptance(@RequestParam(defaultValue = "30") int days) {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(feedbackService.acceptanceReport(user.getTenantId(), days));
    }

    /** Compare prompt versions before promoting one. */
    @GetMapping("/v1/ai/feedback/prompt-versions")
    public ApiResponse<List<Map<String, Object>>> promptVersions(@RequestParam String templateKey) {
        return ApiResponse.success(feedbackService.promptVersionComparison(templateKey));
    }

    /** Explicit consent for one item to be reused as a few-shot example. */
    @PostMapping("/v1/ai/feedback/{id}/allow-example")
    public ApiResponse<Void> allowExample(@PathVariable Long id) {
        User user = utilityService.getLoggedInDataContext();
        feedbackService.grantExampleConsent(id, user.getTenantId());
        return ApiResponse.success();
    }
}
