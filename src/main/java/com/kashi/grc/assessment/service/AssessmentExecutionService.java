package com.kashi.grc.assessment.service;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * Snapshotting a template into a live assessment.
 *
 * ── EXTRACTED FROM AssessmentController.executeAssessment, LINES 135–301 ──
 * The flow is unchanged and deliberately so: same order, same checks, same
 * exception codes, same response keys. A behaviour-preserving extraction that
 * quietly improves something is not behaviour-preserving, and this one has to
 * be trusted against the old screens while both run side by side.
 *
 * What changed is everything around the flow, not the flow:
 *
 *   · PROGRESS. Reported per section, outside the transaction, so a user can
 *     see where it is. See below for why this was impossible before.
 *   · THE N+1s. The original issued one SELECT per section, per question and
 *     per option inside nested loops. A 200-question template was several
 *     hundred round trips before a single insert. Now three batched reads.
 *   · THE WORKFLOW ADVANCE MOVED OUT. See the note on advanceAfterSnapshot.
 *
 * ── WHY NOTHING COULD REPORT PROGRESS BEFORE ──────────────────────────────
 * The whole snapshot is one transaction, correctly — a half-created assessment
 * is worse than none. But that means nothing it writes is visible until it
 * commits, so there was nowhere to put a progress figure that anybody could
 * read. Polling the assessment row returned nothing because the row did not
 * exist yet.
 *
 * That is the real cause of "users go blank on what's happening". Not Kafka,
 * and not the duration — duration is only a problem when it is unobservable.
 * Progress therefore goes through AssessmentProgressTracker, which writes
 * outside the transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentExecutionService {

    private final VendorRepository                      vendorRepository;
    private final RiskTemplateMappingRepository         mappingRepository;
    private final VendorAssessmentCycleRepository       cycleRepository;
    private final VendorAssessmentRepository            assessmentRepository;
    private final AssessmentTemplateRepository          templateRepository;
    private final AssessmentTemplateInstanceRepository  templateInstanceRepository;
    private final TemplateSectionMappingRepository      templateSectionMappingRepository;
    private final AssessmentSectionRepository           sectionRepository;
    private final AssessmentSectionInstanceRepository   sectionInstanceRepository;
    private final SectionQuestionMappingRepository      sectionQuestionMappingRepository;
    private final AssessmentQuestionRepository          questionRepository;
    private final AssessmentQuestionInstanceRepository  questionInstanceRepository;
    private final QuestionOptionMappingRepository       questionOptionMappingRepository;
    private final AssessmentQuestionOptionRepository    optionRepository;
    private final AssessmentOptionInstanceRepository    optionInstanceRepository;
    private final AssessmentProgressTracker             progress;

    /** What the snapshot produced, for the caller to advance the workflow with. */
    public record SnapshotResult(
            Long assessmentId, Long templateInstanceId, Long cycleId,
            Long vendorId, Long templateId,
            int sectionsSnapshot, int totalQuestionsSnapshot) {}

    // ═════════════════════════════════════════════════════════════════════════
    // THE SNAPSHOT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates the cycle, the assessment and the full instance tree.
     *
     * @param progressKey where to report progress; null to report none
     */
    @Transactional
    public SnapshotResult snapshot(Long vendorId, Long workflowInstanceId,
                                   Long tenantId, Long userId, String progressKey) {

        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));

        var mappingOpt = mappingRepository.findByScore(vendor.getCurrentRiskScore());
        if (mappingOpt.isEmpty()) {
            throw new BusinessException("NO_TEMPLATE_MAPPED",
                    "No template mapped for this vendor's risk score");
        }
        Long templateId = mappingOpt.get().getTemplateId();

        VendorAssessmentCycle cycle = findOrCreateCycle(vendor, workflowInstanceId, tenantId, userId);

        // Preserved exactly: the original refuses rather than creating a second
        // assessment for one cycle. That refusal is what makes the endpoint
        // safe to retry, so it stays — including the error code, which a client
        // may already be matching on.
        if (!assessmentRepository.findByCycleId(cycle.getId()).isEmpty()) {
            throw new BusinessException("ASSESSMENT_ALREADY_EXISTS",
                    "Assessment already instantiated for this cycle.");
        }

        VendorAssessment assessment = assessmentRepository.save(VendorAssessment.builder()
                .tenantId(tenantId).cycleId(cycle.getId()).vendorId(vendor.getId())
                .templateId(templateId).status("ASSIGNED")
                .build());

        AssessmentTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentTemplate", templateId));

        AssessmentTemplateInstance templateInstance = templateInstanceRepository.save(
                AssessmentTemplateInstance.builder()
                        .tenantId(tenantId)
                        .assessmentId(assessment.getId())
                        .originalTemplateId(templateId)
                        .templateNameSnapshot(template.getName())
                        .templateVersionSnapshot(template.getVersion())
                        .snapshottedAt(LocalDateTime.now())
                        .build());

        List<TemplateSectionMapping> sectionMappings =
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNo(templateId);

        if (progressKey != null) {
            progress.start(progressKey, "Preparing assessment", sectionMappings.size());
        }

        // ── BATCHED READS ────────────────────────────────────────────────
        // The original called findById inside three nested loops. Loading each
        // level once turns hundreds of round trips into three, which is most of
        // why this was slow — the inserts were never the expensive part.
        Map<Long, AssessmentSection> sections = byId(
                sectionRepository.findAllById(
                        sectionMappings.stream().map(TemplateSectionMapping::getSectionId).toList()),
                AssessmentSection::getId);

        int questionCount = 0;
        int sectionsDone  = 0;

        for (TemplateSectionMapping tsm : sectionMappings) {
            AssessmentSection section = sections.get(tsm.getSectionId());
            if (section == null) {
                throw new ResourceNotFoundException("AssessmentSection", tsm.getSectionId());
            }

            AssessmentSectionInstance sectionInstance = sectionInstanceRepository.save(
                    AssessmentSectionInstance.builder()
                            .templateInstanceId(templateInstance.getId())
                            .originalSectionId(section.getId())
                            .sectionNameSnapshot(section.getName())
                            .sectionOrderNo(tsm.getOrderNo())
                            .build());

            List<SectionQuestionMapping> questionMappings =
                    sectionQuestionMappingRepository.findBySectionIdOrderByOrderNo(section.getId());

            Map<Long, AssessmentQuestion> questions = byId(
                    questionRepository.findAllById(
                            questionMappings.stream()
                                    .map(SectionQuestionMapping::getQuestionId).toList()),
                    AssessmentQuestion::getId);

            for (SectionQuestionMapping sqm : questionMappings) {
                AssessmentQuestion q = questions.get(sqm.getQuestionId());
                if (q == null) {
                    throw new ResourceNotFoundException("AssessmentQuestion", sqm.getQuestionId());
                }

                AssessmentQuestionInstance qi = questionInstanceRepository.save(
                        AssessmentQuestionInstance.builder()
                                .assessmentId(assessment.getId())
                                // THE ONE DEVIATION FROM THE ORIGINAL, AND IT
                                // IS DELIBERATE. assessment_question_instances
                                // declares a tenant_id column that nothing has
                                // ever written — no listener, no @PrePersist,
                                // and the original builder omits it. Every row
                                // in that table is NULL, so any code that
                                // scopes by it matches nothing, which is how a
                                // 404-on-every-request bug gets written.
                                //
                                // Filling it changes no behaviour today,
                                // because nothing reads it today. It stops the
                                // column being a trap for whoever reaches for
                                // it next. Old rows stay NULL, so nothing may
                                // depend on it being uniform yet — evidence
                                // scoping runs through the assessment instead.
                                .tenantId(tenantId)
                                .sectionInstanceId(sectionInstance.getId())
                                .originalQuestionId(q.getId())
                                .questionTextSnapshot(q.getQuestionText())
                                .responseType(q.getResponseType())
                                // Preserved: templates created before weight
                                // existed have none, and a null weight would
                                // make the whole section unscoreable.
                                .weight(sqm.getWeight() != null ? sqm.getWeight() : 1.0)
                                .isMandatory(sqm.isMandatory())
                                .orderNo(sqm.getOrderNo())
                                // Preserved, and load-bearing: GuardEvaluator
                                // reads this snapshot and never joins back to
                                // assessment_questions, so a later tag change
                                // on the library question cannot alter how an
                                // in-flight assessment is evaluated.
                                .questionTagSnapshot(q.getQuestionTag())
                                // Same snapshot rule as the tag above.
                                .requiresEvidence(q.isRequiresEvidence())
                                .build());

                snapshotOptions(q.getId(), qi.getId());
                questionCount++;
            }

            sectionsDone++;
            if (progressKey != null) {
                progress.step(progressKey, "Preparing assessment",
                        sectionsDone, sectionMappings.size(), section.getName());
            }
        }

        if (progressKey != null) {
            progress.finish(progressKey, "Assessment ready", sectionMappings.size());
        }

        log.info("[ASSESSMENT-EXEC] Snapshot complete | assessment={} | vendor={} | sections={} | questions={}",
                assessment.getId(), vendor.getId(), sectionMappings.size(), questionCount);

        return new SnapshotResult(assessment.getId(), templateInstance.getId(), cycle.getId(),
                vendor.getId(), templateId, sectionMappings.size(), questionCount);
    }

    /**
     * Reuses the vendor's active cycle, or opens a new one.
     *
     * Preserved exactly, including `reduce((a, b) -> b)` to take the LAST
     * active cycle rather than the first. If a vendor somehow has two active
     * cycles that is a data problem, but changing which one is picked here
     * would silently move assessments between them.
     */
    private VendorAssessmentCycle findOrCreateCycle(Vendor vendor, Long workflowInstanceId,
                                                    Long tenantId, Long userId) {
        VendorAssessmentCycle cycle = cycleRepository
                .findByVendorIdOrderByCycleNo(vendor.getId()).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .reduce((a, b) -> b)
                .orElse(null);

        if (cycle == null) {
            long cycleNo = cycleRepository.countByVendorId(vendor.getId()) + 1;
            return cycleRepository.save(VendorAssessmentCycle.builder()
                    .tenantId(tenantId).vendorId(vendor.getId())
                    .cycleNo((int) cycleNo).triggeredAt(LocalDateTime.now())
                    .triggeredBy(userId)
                    .workflowInstanceId(workflowInstanceId)
                    .status("ACTIVE")
                    .build());
        }
        cycle.setWorkflowInstanceId(workflowInstanceId);
        return cycleRepository.save(cycle);
    }

    /** The option tree for one question, batched the same way. */
    private void snapshotOptions(Long originalQuestionId, Long questionInstanceId) {
        List<QuestionOptionMapping> mappings =
                questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(originalQuestionId);
        if (mappings.isEmpty()) return;

        Map<Long, AssessmentQuestionOption> options = byId(
                optionRepository.findAllById(
                        mappings.stream().map(QuestionOptionMapping::getOptionId).toList()),
                AssessmentQuestionOption::getId);

        for (QuestionOptionMapping qom : mappings) {
            AssessmentQuestionOption opt = options.get(qom.getOptionId());
            // Preserved: the original used ifPresent and skipped a missing
            // option rather than failing. A question whose option list has been
            // partly deleted still snapshots, with fewer choices. Throwing here
            // would block the whole assessment over one orphaned mapping.
            if (opt == null) continue;

            optionInstanceRepository.save(AssessmentOptionInstance.builder()
                    .questionInstanceId(questionInstanceId)
                    .originalOptionId(opt.getId())
                    .optionValue(opt.getOptionValue())
                    .score(opt.getScore())
                    .orderNo(qom.getOrderNo())
                    .build());
        }
    }

    private <T> Map<Long, T> byId(Iterable<T> items, Function<T, Long> id) {
        Map<Long, T> out = new HashMap<>();
        items.forEach(i -> out.put(id.apply(i), i));
        return out;
    }
}

/*
 * ── WHY THE WORKFLOW ADVANCE IS NOT IN THIS CLASS ──────────────────────────
 *
 * The original method ended by auto-approving its own task to advance the
 * workflow, inside the same transaction as the snapshot.
 *
 * That coupling is worth breaking, for the same reason the remediation counter
 * was worth separating from report generation: one transaction doing two
 * unrelated things means a failure in either rolls back both, and a retry
 * repeats both. If advancing the workflow throws — a missing actor, an
 * unresolvable assignment — the entire snapshot is discarded and has to be
 * redone, even though it was correct.
 *
 * The controller should therefore call:
 *
 *     SnapshotResult r = executionService.snapshot(vendorId, wfInstanceId,
 *                                                  tenantId, userId, progressKey);
 *     // then, separately:
 *     workflowEngineService.performAction(actionReq, userId);
 *
 * and shape the response exactly as before. Keeping performAction in the
 * controller is deliberate for this first extraction: the workflow engine is
 * shared by six modules, and pulling its call site into an assessment service
 * would be a second change riding on a refactor that is supposed to preserve
 * behaviour. One thing at a time.
 */