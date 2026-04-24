package com.kashi.grc.assessment.workflow;

import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the primary artifact ID for VENDOR entity type workflows.
 *
 * For vendor workflows, the artifact is the VendorAssessment linked to
 * the workflow instance via the VendorAssessmentCycle.
 *
 * Lookup chain:
 *   WorkflowInstance.entityId (vendorId)
 *     → VendorAssessmentCycle where workflowInstanceId = instance.id
 *     → VendorAssessment where cycleId = cycle.id
 *     → returns assessment.id
 *
 * This class lives in the assessment module — it has no knowledge of the
 * workflow engine internals beyond the WorkflowEntityResolver contract.
 * The workflow engine has no knowledge of assessments beyond calling this.
 *
 * Adding Audit module = create AuditEntityResolver in the audit module.
 * This file never needs to change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorAssessmentEntityResolver implements WorkflowEntityResolver {

    private final VendorAssessmentCycleRepository cycleRepository;
    private final VendorAssessmentRepository      assessmentRepository;

    @Override
    public String entityType() {
        return "VENDOR";
    }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        // Direct lookup by workflowInstanceId — single indexed query instead of
        // load-all-cycles-for-vendor then filter in Java (which caused N+1 on inbox load).
        return cycleRepository
                .findByWorkflowInstanceId(instance.getId())
                .flatMap(cycle ->
                        assessmentRepository.findByCycleId(cycle.getId())
                                .stream()
                                .findFirst()
                )
                .map(a -> {
                    log.debug("[VENDOR-RESOLVER] assessmentId={} vendorId={} instanceId={}",
                            a.getId(), instance.getEntityId(), instance.getId());
                    return a.getId();
                })
                .orElseGet(() -> {
                    log.debug("[VENDOR-RESOLVER] No assessment found for instanceId={}",
                            instance.getId());
                    return null;
                });
    }
}