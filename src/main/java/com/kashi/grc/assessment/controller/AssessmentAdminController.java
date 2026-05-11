package com.kashi.grc.assessment.controller;

import com.kashi.grc.assessment.domain.AssessmentOptionInstance;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.AssessmentResponse;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.repository.AssessmentOptionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentResponseRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AssessmentAdminController — admin-only operations isolated from AssessmentController.
 *
 * Kept separate so compilation errors in AssessmentController never
 * prevent these endpoints from being registered.
 */
@Slf4j
@RestController
@Tag(name = "Assessment Admin", description = "Admin: score recalculation, data migrations")
@RequiredArgsConstructor
public class AssessmentAdminController {

    private final VendorAssessmentRepository           assessmentRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final AssessmentOptionInstanceRepository   optionInstanceRepository;
    private final AssessmentResponseRepository         responseRepository;
    private final UtilityService                       utilityService;
    private final ApplicationEventPublisher            eventPublisher;

    /**
     * POST /v1/assessments/{assessmentId}/recalculate-scores
     *
     * Re-derives normalised scoreEarned for every existing response using the
     * industry-standard formula: (optionScore / maxOptionScore) × weight.
     *
     * Use for assessments answered before normalised scoring was deployed.
     * Idempotent — safe to call multiple times.
     */
    @PostMapping("/v1/assessments/{assessmentId}/recalculate-scores")
    @Transactional
    @Operation(summary = "Re-derive normalised scoreEarned for all responses (fixes pre-migration data)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recalculateScores(
            @PathVariable Long assessmentId,
            @RequestParam(required = false) Long taskId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        log.info("[RECALCULATE] Starting | assessmentId={} | by={}", assessmentId, userId);

        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        List<AssessmentQuestionInstance> allQs =
                questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessmentId);

        int updated = 0;
        int skipped = 0;

        for (AssessmentQuestionInstance qi : allQs) {

            AssessmentResponse resp = responseRepository
                    .findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(assessmentId, qi.getId())
                    .orElse(null);

            if (resp == null) continue;

            double weight = qi.getWeight() != null ? qi.getWeight() : 1.0;
            Double newScore = null;

            if ("SINGLE_CHOICE".equals(qi.getResponseType())
                    && resp.getSelectedOptionInstanceId() != null) {

                AssessmentOptionInstance selectedOpt =
                        optionInstanceRepository.findById(resp.getSelectedOptionInstanceId()).orElse(null);
                if (selectedOpt != null && selectedOpt.getScore() != null) {
                    double maxScore = optionInstanceRepository.maxScoreByQuestionInstanceId(qi.getId());
                    newScore = maxScore > 0
                            ? (selectedOpt.getScore() / maxScore) * weight
                            : weight;
                } else {
                    newScore = weight;
                }

            } else if ("MULTI_CHOICE".equals(qi.getResponseType())
                    && resp.getResponseText() != null
                    && resp.getResponseText().startsWith("[")) {

                try {
                    Long[] ids = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(resp.getResponseText(), Long[].class);
                    Set<Long> selectedIds = new HashSet<>(Arrays.asList(ids));
                    double sumAll = optionInstanceRepository.sumScoreByQuestionInstanceId(qi.getId());
                    if (sumAll > 0) {
                        double sumSelected = selectedIds.stream()
                                .mapToDouble(id -> optionInstanceRepository.findById(id)
                                        .map(o -> o.getScore() != null ? o.getScore() : 0.0)
                                        .orElse(0.0))
                                .sum();
                        newScore = (sumSelected / sumAll) * weight;
                    } else {
                        newScore = selectedIds.isEmpty() ? 0.0 : weight;
                    }
                } catch (Exception ignored) {
                    skipped++;
                    continue;
                }

            } else if (resp.getResponseText() != null
                    && !resp.getResponseText().isBlank()
                    && !resp.getResponseText().startsWith("[FILE_UPLOADED")) {
                // TEXT / NUMERIC / DATE — binary: answered = full weight
                newScore = weight;

            } else if (resp.getResponseText() == null
                    && resp.getSelectedOptionInstanceId() == null
                    && resp.getScoreEarned() == null) {
                // Shell row (reviewer auto-FAIL stub) — skip
                skipped++;
                continue;
            }

            if (newScore != null && !newScore.equals(resp.getScoreEarned())) {
                responseRepository.upsertResponse(
                        resp.getTenantId(),
                        assessmentId,
                        qi.getId(),
                        resp.getResponseText(),
                        resp.getSelectedOptionInstanceId(),
                        newScore,
                        resp.getSubmittedBy() != null ? resp.getSubmittedBy() : userId,
                        resp.getSubmittedAt() != null ? resp.getSubmittedAt() : LocalDateTime.now()
                );
                updated++;
            } else {
                skipped++;
            }
        }

        // Consolidate and persist totalEarnedScore + totalPossibleScore
        double newTotal = responseRepository.sumReviewerAdjustedScoreByAssessmentId(assessmentId);
        double possible = allQs.stream()
                .mapToDouble(q -> q.getWeight() != null ? q.getWeight() : 1.0)
                .sum();

        assessment.setTotalEarnedScore(newTotal);
        assessment.setTotalPossibleScore(possible);
        assessmentRepository.save(assessment);

        log.info("[RECALCULATE] Done | assessmentId={} | updated={} | skipped={} | score={}/{} ({}%)",
                assessmentId, updated, skipped, newTotal, possible,
                possible > 0 ? Math.round(newTotal / possible * 100) : 0);

        // Fire SCORES_CONSOLIDATED to advance workflow gate if taskId provided
        if (taskId != null) {
            try {
                eventPublisher.publishEvent(
                        com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                                "SCORES_CONSOLIDATED", taskId, userId, "VENDOR_ASSESSMENT", assessmentId));
            } catch (Exception e) {
                log.warn("[RECALCULATE] SCORES_CONSOLIDATED event skipped: {}", e.getMessage());
            }
        }

        long compliancePct = possible > 0 ? Math.round(newTotal / possible * 100) : 0;
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",      assessmentId,
                "responsesUpdated",  updated,
                "responsesSkipped",  skipped,
                "newTotalEarned",    newTotal,
                "totalPossible",     possible,
                "compliancePct",     compliancePct
        )));
    }

    /**
     * PUT /v1/assessments/{assessmentId}/findings
     *
     * Admin: directly set the combined findings text.
     * Use when reviewer findings were lost due to the old overwrite bug,
     * or to manually combine/edit findings before report finalization.
     * Overwrites whatever is currently stored — admin takes responsibility.
     *
     * Format tip: use "--- Reviewer Name · Date ---" headers to separate
     * multiple reviewers' findings for display in the report page.
     */
    @PutMapping("/v1/assessments/{assessmentId}/findings")
    @Transactional
    @Operation(summary = "Admin: directly set consolidated findings text (overwrites)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateFindings(
            @PathVariable Long assessmentId,
            @RequestBody Map<String, String> body) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        String findings = body.getOrDefault("findings", "");
        assessment.setReviewFindings(findings.isBlank() ? null : findings.trim());
        assessmentRepository.save(assessment);

        log.info("[FINDINGS-ADMIN] Updated | assessmentId={} | by={} | chars={}",
                assessmentId, userId, findings.length());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId", assessmentId,
                "updated",      true,
                "chars",        findings.length()
        )));
    }
}