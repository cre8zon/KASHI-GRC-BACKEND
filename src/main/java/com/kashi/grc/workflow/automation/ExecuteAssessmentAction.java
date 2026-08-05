package com.kashi.grc.workflow.automation;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.dto.internal.TemplateStructureSnapshot;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.vendor.domain.RiskTemplateMapping;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.domain.VendorTemplateSelection;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.vendor.repository.VendorTemplateSelectionRepository;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private final VendorTemplateSelectionRepository    templateSelectionRepository;
    private final VendorAssessmentCycleRepository      cycleRepository;
    private final VendorAssessmentRepository           assessmentRepository;
    private final AssessmentTemplateRepository         templateRepository;
    private final AssessmentTemplateInstanceRepository templateInstanceRepository;
    private final com.kashi.grc.assessment.service.AssessmentTemplateStructureCacheService templateStructureCacheService;
    private final com.kashi.grc.common.jdbc.JdbcBatchInsertHelper jdbcBatchInsertHelper;

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

        // ── Resolve templateId ────────────────────────────────────────────────
        // Primary path: QUEUE_ASSESSMENT_CANDIDATES ran before this step and an
        // ORG_ADMIN / ORG_OWNER made their selection on the manual step.
        // Fallback path: blueprint uses the old single-step style — find the one
        // mapped template directly from the risk score (backward compatible).
        Long templateId;
        java.util.Optional<VendorTemplateSelection> selectionOpt =
                templateSelectionRepository.findByWorkflowInstanceId(wi.getId());

        if (selectionOpt.isPresent()) {
            VendorTemplateSelection sel = selectionOpt.get();
            if (sel.getSelectedTemplateId() == null) {
                log.error("[EXECUTE_ASSESSMENT] Template selection exists but no template chosen yet " +
                                "| workflowInstanceId={} — SELECT step must complete before EXECUTE_ASSESSMENT",
                        wi.getId());
                return false;
            }
            templateId = sel.getSelectedTemplateId();
            log.info("[EXECUTE_ASSESSMENT] Using human-selected templateId={} (tier={}) | workflowInstanceId={}",
                    templateId, sel.getRiskTierLabel(), wi.getId());
        } else {
            // Backward-compatible fallback: no QUEUE step in this blueprint,
            // pick the single template mapped for this score.
            Optional<RiskTemplateMapping> mappingOpt =
                    mappingRepository.findByScore(vendor.getCurrentRiskScore());
            if (mappingOpt.isEmpty()) {
                log.error("[EXECUTE_ASSESSMENT] No template mapped for risk score={} | vendorId={}",
                        vendor.getCurrentRiskScore(), vendor.getId());
                return false;
            }
            templateId = mappingOpt.get().getTemplateId();
            log.info("[EXECUTE_ASSESSMENT] Using score-mapped templateId={} (fallback, no selection row) | vendorId={}",
                    templateId, vendor.getId());
        }

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
        // Read side: was 5 bulk-load queries every single instantiation, even
        // though a template's structure only changes on a rare admin edit.
        // Now: one call to the cached structure service — a Redis GET on a
        // cache hit instead of 5 MySQL round trips. See
        // AssessmentTemplateStructureCacheService for the cache design.
        //
        // Write side: JDBC batch inserts, tiered (sections, then questions,
        // then options) via Statement.RETURN_GENERATED_KEYS — see
        // jdbcBatchInsertHelper.batchInsertAndGetIds() below for why raw JDBC instead of saveAll()
        // is required here (Hibernate cannot batch INSERTs for
        // IDENTITY-strategy entities, which is everything in this app).

        TemplateStructureSnapshot structure = templateStructureCacheService.getStructure(templateId);

        // Single timestamp for the whole batch — mirrors what JPA auditing
        // (@CreatedDate/@LastModifiedDate) would have stamped on each row
        // individually; using one value for the batch is the standard
        // approach and the sub-millisecond difference has no behavioral
        // meaning anywhere downstream (nothing orders these instances by
        // exact creation instant within a single assessment).
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        // ── Tier 1: section instances ──────────────────────────────────────
        List<Object[]> sectionRows = structure.sections().stream()
                .map(s -> new Object[]{
                        templateInstance.getId(), s.librarySectionId(),
                        s.sectionName(), s.orderNo(), now, now
                })
                .toList();
        List<Long> sectionInstanceIds = jdbcBatchInsertHelper.batchInsertAndGetIds(
                "INSERT INTO assessment_section_instances " +
                        "(template_instance_id, original_section_id, section_name_snapshot, section_order_no, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                sectionRows);

        // ── Tier 2: question instances ──────────────────────────────────────
        // Flatten (sectionInstanceId, question) pairs in the same order as
        // sectionRows/sectionInstanceIds so index i lines up across both.
        record QuestionPair(Long sectionInstanceId, TemplateStructureSnapshot.QuestionSnapshot question) {}
        List<QuestionPair> flatQuestions = new ArrayList<>();
        for (int i = 0; i < structure.sections().size(); i++) {
            Long sectionInstanceId = sectionInstanceIds.get(i);
            for (var q : structure.sections().get(i).questions()) {
                flatQuestions.add(new QuestionPair(sectionInstanceId, q));
            }
        }

        List<Object[]> questionRows = flatQuestions.stream()
                .map(p -> new Object[]{
                        assessment.getId(), p.sectionInstanceId(), p.question().libraryQuestionId(),
                        p.question().questionText(), p.question().responseType(),
                        p.question().weight(), p.question().mandatory(), p.question().orderNo(),
                        p.question().questionTag(), now, now
                })
                .toList();
        List<Long> questionInstanceIds = jdbcBatchInsertHelper.batchInsertAndGetIds(
                "INSERT INTO assessment_question_instances " +
                        "(assessment_id, section_instance_id, original_question_id, question_text_snapshot, " +
                        "response_type, weight, is_mandatory, order_no, question_tag_snapshot, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                questionRows);
        int questionCount = questionInstanceIds.size();

        // ── Tier 3: option instances ─────────────────────────────────────────
        List<Object[]> optionRows = new ArrayList<>();
        for (int i = 0; i < flatQuestions.size(); i++) {
            Long questionInstanceId = questionInstanceIds.get(i);
            for (var opt : flatQuestions.get(i).question().options()) {
                optionRows.add(new Object[]{
                        questionInstanceId, opt.libraryOptionId(), opt.optionValue(),
                        opt.score(), opt.orderNo(), now, now
                });
            }
        }
        jdbcBatchInsertHelper.batchInsertAndGetIds(
                "INSERT INTO assessment_option_instances " +
                        "(question_instance_id, original_option_id, option_value, score, order_no, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                optionRows);

        log.info("[EXECUTE_ASSESSMENT] Done | assessmentId={} | templateInstanceId={} | " +
                        "sections={} | questions={}",
                assessment.getId(), templateInstance.getId(),
                structure.sections().size(), questionCount);

        return true;
    }
}