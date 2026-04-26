package com.kashi.grc.workflow.automation;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.vendor.domain.RiskTemplateMapping;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AutomatedActionHandler for key "EXECUTE_ASSESSMENT".
 *
 * Extracts the assessment instantiation logic that previously lived in
 * AssessmentController.executeAssessment() into a proper @Component.
 *
 * WHY MOVED:
 *   Business logic must not live in controllers. The old endpoint required
 *   a taskId, a self-assigned task, and a manual frontend call — all of which
 *   were workarounds for the lack of an automation registry. Now this fires
 *   automatically when a SYSTEM step with automatedAction="EXECUTE_ASSESSMENT"
 *   starts, with no frontend involvement.
 *
 * WHAT IT DOES:
 *   1. Loads vendor from workflowInstance.entityId
 *   2. Finds the risk→template mapping for vendor's current risk score
 *   3. Creates or reuses the active VendorAssessmentCycle
 *   4. Creates VendorAssessment (status=ASSIGNED)
 *   5. Snapshots AssessmentTemplateInstance, SectionInstances, QuestionInstances,
 *      OptionInstances — locking the template version at trigger time
 *
 * After returning true, WorkflowEngineService auto-approves the SYSTEM step
 * and advances the workflow to the next step (VRM assignment).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecuteAssessmentAction implements AutomatedActionHandler {

    private final VendorRepository                     vendorRepository;
    private final RiskTemplateMappingRepository        mappingRepository;
    private final VendorAssessmentCycleRepository      cycleRepository;
    private final VendorAssessmentRepository           assessmentRepository;
    private final AssessmentTemplateRepository         templateRepository;
    private final AssessmentTemplateInstanceRepository templateInstanceRepository;
    private final AssessmentSectionInstanceRepository  sectionInstanceRepository;
    private final AssessmentSectionRepository          sectionRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final AssessmentQuestionRepository         questionRepository;
    private final AssessmentOptionInstanceRepository   optionInstanceRepository;
    private final AssessmentQuestionOptionRepository   optionRepository;
    private final TemplateSectionMappingRepository     templateSectionMappingRepository;
    private final SectionQuestionMappingRepository     sectionQuestionMappingRepository;
    private final QuestionOptionMappingRepository      questionOptionMappingRepository;

    @Override
    public String actionKey() {
        return "EXECUTE_ASSESSMENT";
    }

    @Override
    @Transactional
    public boolean execute(AutomatedActionContext ctx) {
        WorkflowInstance wi       = ctx.getWorkflowInstance();
        Long             tenantId = ctx.getTenantId();
        Long             userId   = ctx.getInitiatedBy();

        log.info("[EXECUTE_ASSESSMENT] Starting | workflowInstanceId={} | entityId={}",
                wi.getId(), wi.getEntityId());

        // ── Load vendor ───────────────────────────────────────────────────────
        Vendor vendor = vendorRepository.findById(wi.getEntityId()).orElse(null);
        if (vendor == null) {
            log.error("[EXECUTE_ASSESSMENT] Vendor not found | entityId={}", wi.getEntityId());
            return false;
        }

        // ── Find risk→template mapping for this vendor's score ────────────────
        Optional<RiskTemplateMapping> mappingOpt =
                mappingRepository.findByScore(vendor.getCurrentRiskScore());
        if (mappingOpt.isEmpty()) {
            log.error("[EXECUTE_ASSESSMENT] No template mapped for risk score={} | vendorId={}",
                    vendor.getCurrentRiskScore(), vendor.getId());
            return false;
        }
        Long templateId = mappingOpt.get().getTemplateId();

        // ── Create or reuse the active cycle ──────────────────────────────────
        VendorAssessmentCycle cycle = cycleRepository
                .findByVendorIdOrderByCycleNo(vendor.getId()).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .reduce((a, b) -> b)
                .orElse(null);

        if (cycle == null) {
            long cycleNo = cycleRepository.countByVendorId(vendor.getId()) + 1;
            cycle = VendorAssessmentCycle.builder()
                    .tenantId(tenantId)
                    .vendorId(vendor.getId())
                    .cycleNo((int) cycleNo)
                    .triggeredAt(LocalDateTime.now())
                    .triggeredBy(userId)
                    .workflowInstanceId(wi.getId())
                    .status("ACTIVE")
                    .build();
            cycleRepository.save(cycle);
            log.info("[EXECUTE_ASSESSMENT] New cycle created | cycleId={} | cycleNo={}",
                    cycle.getId(), cycle.getCycleNo());
        } else {
            cycle.setWorkflowInstanceId(wi.getId());
            cycleRepository.save(cycle);
            log.info("[EXECUTE_ASSESSMENT] Reusing existing cycle | cycleId={}", cycle.getId());
        }

        // ── Guard: prevent duplicate assessments for the same cycle ───────────
        boolean assessmentExists = !assessmentRepository.findByCycleId(cycle.getId()).isEmpty();
        if (assessmentExists) {
            log.warn("[EXECUTE_ASSESSMENT] Assessment already exists for cycleId={} — skipping",
                    cycle.getId());
            // Return true so the SYSTEM step still auto-approves and workflow advances
            return true;
        }

        // ── Create VendorAssessment ───────────────────────────────────────────
        VendorAssessment assessment = VendorAssessment.builder()
                .tenantId(tenantId)
                .cycleId(cycle.getId())
                .vendorId(vendor.getId())
                .templateId(templateId)
                .status("ASSIGNED")
                .build();
        assessmentRepository.save(assessment);

        // ── Snapshot AssessmentTemplateInstance ───────────────────────────────
        AssessmentTemplate template = templateRepository.findById(templateId).orElse(null);
        if (template == null) {
            log.error("[EXECUTE_ASSESSMENT] Template not found | templateId={}", templateId);
            return false;
        }

        AssessmentTemplateInstance templateInstance = AssessmentTemplateInstance.builder()
                .tenantId(tenantId)
                .assessmentId(assessment.getId())
                .originalTemplateId(templateId)
                .templateNameSnapshot(template.getName())
                .templateVersionSnapshot(template.getVersion())
                .snapshottedAt(LocalDateTime.now())
                .build();
        templateInstanceRepository.save(templateInstance);

        // ── Snapshot sections + questions + options ───────────────────────────
        // OLD: individual save() per section, question, option = 448 round-trips
        //      × 20ms Aiven RTT = ~9 seconds just for this block.
        // NEW: bulk-load all data in a handful of queries, build all objects
        //      in memory, then saveAll() each type in one batched INSERT.
        //      Result: 5 queries to read + 3 saveAll() calls = ~8 round-trips
        //      instead of 448 — typically reduces this block from 9s to <1s.

        int questionCount = 0;
        List<TemplateSectionMapping> sectionMappings =
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNo(templateId);

        // Bulk-load all sections for this template in one query
        List<Long> sectionIds = sectionMappings.stream()
                .map(TemplateSectionMapping::getSectionId).toList();
        Map<Long, AssessmentSection> sectionMap = sectionRepository.findAllById(sectionIds)
                .stream().collect(java.util.stream.Collectors.toMap(AssessmentSection::getId, s -> s));

        // Bulk-load all question mappings for all sections in one query
        List<SectionQuestionMapping> allQuestionMappings =
                sectionQuestionMappingRepository.findBySectionIdInOrderByOrderNo(sectionIds);

        List<Long> questionIds = allQuestionMappings.stream()
                .map(SectionQuestionMapping::getQuestionId).toList();

        // Bulk-load all questions in one query
        Map<Long, AssessmentQuestion> questionMap = questionRepository.findAllById(questionIds)
                .stream().collect(java.util.stream.Collectors.toMap(AssessmentQuestion::getId, q -> q));

        // Bulk-load all option mappings for all questions in one query
        List<QuestionOptionMapping> allOptionMappings =
                questionOptionMappingRepository.findByQuestionIdInOrderByOrderNo(questionIds);
        List<Long> optionIds = allOptionMappings.stream()
                .map(QuestionOptionMapping::getOptionId).toList();
        Map<Long, AssessmentQuestionOption> optionMap = optionRepository.findAllById(optionIds)
                .stream().collect(java.util.stream.Collectors.toMap(AssessmentQuestionOption::getId, o -> o));

        // Group question mappings by section for easy lookup
        Map<Long, List<SectionQuestionMapping>> questionsBySectionId = allQuestionMappings.stream()
                .collect(java.util.stream.Collectors.groupingBy(SectionQuestionMapping::getSectionId));
        // Group option mappings by question for easy lookup
        Map<Long, List<QuestionOptionMapping>> optionsByQuestionId = allOptionMappings.stream()
                .collect(java.util.stream.Collectors.groupingBy(QuestionOptionMapping::getQuestionId));

        // Build + saveAll section instances
        List<AssessmentSectionInstance> sectionInstances = new java.util.ArrayList<>();
        for (TemplateSectionMapping tsm : sectionMappings) {
            AssessmentSection section = sectionMap.get(tsm.getSectionId());
            if (section == null) continue;
            sectionInstances.add(AssessmentSectionInstance.builder()
                    .templateInstanceId(templateInstance.getId())
                    .originalSectionId(section.getId())
                    .sectionNameSnapshot(section.getName())
                    .sectionOrderNo(tsm.getOrderNo())
                    .build());
        }
        sectionInstanceRepository.saveAll(sectionInstances);

        // Build + saveAll question instances (sections now have IDs from saveAll)
        List<AssessmentQuestionInstance> questionInstances = new java.util.ArrayList<>();
        Map<Long, AssessmentSectionInstance> sectionInstanceBySectionId = new java.util.HashMap<>();
        for (int i = 0; i < sectionMappings.size(); i++) {
            AssessmentSection section = sectionMap.get(sectionMappings.get(i).getSectionId());
            if (section == null || i >= sectionInstances.size()) continue;
            sectionInstanceBySectionId.put(section.getId(), sectionInstances.get(i));
        }

        for (Map.Entry<Long, AssessmentSectionInstance> entry : sectionInstanceBySectionId.entrySet()) {
            Long sectionId = entry.getKey();
            AssessmentSectionInstance si = entry.getValue();
            List<SectionQuestionMapping> qMappings = questionsBySectionId.getOrDefault(sectionId, List.of());
            for (SectionQuestionMapping sqm : qMappings) {
                AssessmentQuestion q = questionMap.get(sqm.getQuestionId());
                if (q == null) continue;
                questionInstances.add(AssessmentQuestionInstance.builder()
                        .assessmentId(assessment.getId())
                        .sectionInstanceId(si.getId())
                        .originalQuestionId(q.getId())
                        .questionTextSnapshot(q.getQuestionText())
                        .responseType(q.getResponseType())
                        .weight(sqm.getWeight())
                        .isMandatory(sqm.isMandatory())
                        .orderNo(sqm.getOrderNo())
                        .build());
                questionCount++;
            }
        }
        questionInstanceRepository.saveAll(questionInstances);

        // Build + saveAll option instances
        List<AssessmentOptionInstance> optionInstances = new java.util.ArrayList<>();
        for (AssessmentQuestionInstance qi : questionInstances) {
            List<QuestionOptionMapping> oMappings =
                    optionsByQuestionId.getOrDefault(qi.getOriginalQuestionId(), List.of());
            for (QuestionOptionMapping qom : oMappings) {
                AssessmentQuestionOption opt = optionMap.get(qom.getOptionId());
                if (opt == null) continue;
                optionInstances.add(AssessmentOptionInstance.builder()
                        .questionInstanceId(qi.getId())
                        .originalOptionId(opt.getId())
                        .optionValue(opt.getOptionValue())
                        .score(opt.getScore())
                        .orderNo(qom.getOrderNo())
                        .build());
            }
        }
        optionInstanceRepository.saveAll(optionInstances);

        log.info("[EXECUTE_ASSESSMENT] Done | assessmentId={} | templateInstanceId={} | " +
                        "sections={} | questions={}",
                assessment.getId(), templateInstance.getId(),
                sectionMappings.size(), questionCount);

        return true;
    }
}