package com.kashi.grc.assessment.automation;

import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.domain.DocumentLink;
import com.kashi.grc.document.repository.DocumentLinkRepository;
import com.kashi.grc.document.repository.DocumentRepository;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GenerateAssessmentReportAction — automated workflow action (step 14).
 *
 * Triggered by the workflow engine when the final approval is given.
 * Creates report v1 using the unified document system — no separate
 * assessment_reports table (dropped in V4 migration).
 *
 * ── WHAT THIS DOES ───────────────────────────────────────────────────────────
 *  1. Resolves the VendorAssessment from the workflow instance
 *  2. Computes compliance score from question/response data
 *  3. Snapshots open remediation + clarification counts
 *  4. Builds generatedData JSONB (replaces old assessment_reports columns)
 *  5. Uploads report to S3 via StorageService.uploadSystemDocument()
 *  6. Creates Document row (type=GENERATED_REPORT, source_module=VENDOR_ASSESSMENT)
 *  7. Creates DocumentLink row (entity_type=ASSESSMENT, link_type=REPORT)
 *  8. Marks VendorAssessment + VendorAssessmentCycle as COMPLETED
 *
 * ── SCALABILITY ───────────────────────────────────────────────────────────────
 * The same two-table pattern (Document + DocumentLink) works for every future
 * module report with zero new tables:
 *   Audit report:          source_module=AUDIT,   entity_type=AUDIT
 *   Policy summary:        source_module=POLICY,  entity_type=POLICY
 *   Risk register export:  source_module=RISK,    entity_type=RISK
 *
 * ── PDF GENERATION ────────────────────────────────────────────────────────────
 * Currently stores a placeholder byte array — the document infrastructure is
 * complete. To generate a real PDF, inject a ReportPdfGeneratorService:
 *   byte[] pdfBytes = pdfGenerator.generate(assessment, reportData);
 * and pass those bytes to storeGeneratedDocument().
 * Apache PDFBox 3.x (free) or iText 7 (commercial) both work well.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateAssessmentReportAction implements AutomatedActionHandler {

    private final VendorAssessmentCycleRepository      cycleRepository;
    private final VendorAssessmentRepository           assessmentRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final AssessmentResponseRepository         responseRepository;
    private final ActionItemRepository                 actionItemRepository;
    private final DocumentRepository                   documentRepository;
    private final DocumentLinkRepository               documentLinkRepository;
    private final StorageService                       storageService;

    // Inject when PDF generation is ready:
    // private final ReportPdfGeneratorService pdfGenerator;

    @Override
    public String actionKey() {
        return "GENERATE_ASSESSMENT_REPORT";
    }

    @Override
    @Transactional
    public boolean execute(AutomatedActionContext ctx) {
        WorkflowInstance wi = ctx.getWorkflowInstance();

        log.info("[GENERATE_ASSESSMENT_REPORT] Starting | workflowInstanceId={} | entityId={}",
                wi.getId(), wi.getEntityId());

        // ── 1. Resolve cycle + assessment ─────────────────────────────────
        VendorAssessmentCycle cycle = cycleRepository
                .findByVendorIdOrderByCycleNo(wi.getEntityId())
                .stream()
                .filter(c -> wi.getId().equals(c.getWorkflowInstanceId()))
                .findFirst()
                .orElse(null);

        if (cycle == null) {
            log.error("[GENERATE_ASSESSMENT_REPORT] No cycle for workflowInstance={}", wi.getId());
            return false;
        }

        List<VendorAssessment> assessments = assessmentRepository.findByCycleId(cycle.getId());
        if (assessments.isEmpty()) {
            log.error("[GENERATE_ASSESSMENT_REPORT] No assessment for cycleId={}", cycle.getId());
            return false;
        }

        VendorAssessment assessment = assessments.get(0);
        Long assessmentId = assessment.getId();
        Long tenantId     = assessment.getTenantId();
        Long actorId      = wi.getInitiatedBy() != null ? wi.getInitiatedBy() : 0L;

        // ── 2. Idempotency check — don't generate twice ────────────────────
        boolean alreadyGenerated = !documentLinkRepository
                .findActiveByEntity("ASSESSMENT", assessmentId, "REPORT")
                .isEmpty();
        if (alreadyGenerated) {
            log.warn("[GENERATE_ASSESSMENT_REPORT] Already generated — skipping | assessmentId={}", assessmentId);
            return true;
        }

        // ── 3. Compute scores ──────────────────────────────────────────────
        double totalPossible = questionInstanceRepository
                .findByAssessmentIdOrderByOrderNo(assessmentId)
                .stream()
                .mapToDouble(q -> q.getWeight() != null ? q.getWeight() : 1.0)
                .sum();

        double totalEarned = responseRepository.sumScoreByAssessmentId(assessmentId);
        double compliancePct = totalPossible > 0
                ? Math.round((totalEarned / totalPossible) * 10000.0) / 100.0
                : 0.0;

        // ── 4. Snapshot open item counts ───────────────────────────────────
        int openRemediation   = countOpenItemsByType(assessment, "REMEDIATION_REQUEST");
        int openClarification = countOpenItemsByType(assessment, "CLARIFICATION");

        // ── 5. Build generatedData (replaces old assessment_reports columns) ─
        Map<String, Object> generatedData = new HashMap<>();
        generatedData.put("reportVersion",          1);
        generatedData.put("compliancePct",          compliancePct);
        generatedData.put("totalEarnedScore",       totalEarned);
        generatedData.put("totalPossibleScore",     totalPossible);
        generatedData.put("riskRating",             assessment.getRiskRating() != null ? assessment.getRiskRating() : "");
        generatedData.put("openRemediationCount",   openRemediation);
        generatedData.put("openClarificationCount", openClarification);
        generatedData.put("triggerEvent",           "INITIAL");
        generatedData.put("remarks",                "Initial report generated at workflow completion");

        // ── 6. Generate PDF bytes (stub — replace with real PDF generator) ─
        // When ready: byte[] pdfBytes = pdfGenerator.generate(assessment, generatedData);
        byte[] pdfBytes = new byte[0]; // placeholder — 0-byte stub

        String reportFilename = String.format(
                "vendor-assessment-report-v1-assessment-%d.pdf", assessmentId);

        // ── 7. Upload to S3 + create Document + DocumentLink ────────────
        // Only create the Document row when a real PDF is generated and uploaded.
        // With an empty stub (pdfBytes.length == 0), skip document creation entirely —
        // the Document table requires real S3 fields (s3Key, storagePath, fileSize etc.)
        // that we cannot populate without an actual upload.
        // Wire in a PDF generator here when ready:
        //   byte[] pdfBytes = pdfGenerator.generate(assessment, generatedData);
        if (pdfBytes.length > 0) {
            try {
                StorageService.ServerUploadResult uploadResult = storageService.uploadSystemDocument(
                        tenantId, actorId, pdfBytes, reportFilename,
                        "application/pdf", "VENDOR_ASSESSMENT");
                String s3Key = uploadResult.getS3Key();

                Document reportDoc = Document.builder()
                        .tenantId(tenantId)
                        .uploadedBy(actorId)
                        .fileName(reportFilename)
                        .title(String.format("Vendor Assessment Report v1 — Assessment #%d", assessmentId))
                        .mimeType("application/pdf")
                        .documentType("GENERATED_REPORT")
                        .sourceModule("VENDOR_ASSESSMENT")
                        .generatedData(generatedData)
                        .s3Key(s3Key)
                        .s3Bucket(storageService.getBucket())
                        .storagePath(s3Key)
                        .status("ACTIVE")
                        .version(1)
                        .fileSize(uploadResult.getContentLength())
                        .contentLength(uploadResult.getContentLength())
                        .checksumSha256(uploadResult.getChecksumSha256())
                        .build();
                documentRepository.save(reportDoc);

                documentLinkRepository.save(DocumentLink.builder()
                        .tenantId(tenantId)
                        .documentId(reportDoc.getId())
                        .entityType("ASSESSMENT")
                        .entityId(assessmentId)
                        .linkType("REPORT")
                        .createdBy(actorId)
                        .createdAt(LocalDateTime.now())
                        .notes("Auto-generated at workflow completion")
                        .build());

                log.info("[GENERATE_ASSESSMENT_REPORT] Report uploaded | assessmentId={} | docId={} | s3Key={}",
                        assessmentId, reportDoc.getId(), s3Key);

            } catch (Exception e) {
                // S3 upload failed — log but don't abort. Assessment still completes.
                // The report can be re-generated via POST /v1/assessments/:id/generate-report
                log.error("[GENERATE_ASSESSMENT_REPORT] S3 upload failed: {}", e.getMessage(), e);
            }
        } else {
            log.info("[GENERATE_ASSESSMENT_REPORT] PDF stub — skipping document creation. " +
                    "Wire in a PDF generator to produce a real report | assessmentId={}", assessmentId);
        }

        // ── 8. Mark assessment + cycle COMPLETED ──────────────────────────
        assessment.setStatus("COMPLETED");
        assessment.setTotalEarnedScore(totalEarned);
        assessmentRepository.save(assessment);

        cycle.setStatus("COMPLETED");
        cycleRepository.save(cycle);

        log.info("[GENERATE_ASSESSMENT_REPORT] Complete | assessmentId={} | " +
                        "compliance={}% | openRemediation={} | riskRating={}",
                assessmentId, compliancePct, openRemediation,
                assessment.getRiskRating() != null ? assessment.getRiskRating() : "not set");

        return true;
    }

    /**
     * Count open action items of a specific remediation type for this assessment's questions.
     * Used to snapshot how many items were still open at report generation time.
     */
    private int countOpenItemsByType(VendorAssessment assessment, String remediationType) {
        return (int) actionItemRepository.findAll(
                ActionItemSpecification.forTenant(assessment.getTenantId())
                        .and((root, q, cb) -> cb.equal(root.get("remediationType"), remediationType))
                        .and(ActionItemSpecification.open())
        ).stream().filter(ai ->
                questionInstanceRepository.findById(ai.getEntityId())
                        .map(qi -> assessment.getId().equals(qi.getAssessmentId()))
                        .orElse(false)
        ).count();
    }
}