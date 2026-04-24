package com.kashi.grc.vendor.service;

import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.usermanagement.service.user.UserService;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.dto.request.VendorOnboardRequest;
import com.kashi.grc.vendor.dto.response.VendorOnboardResponse;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.repository.WorkflowStepRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorServiceImpl implements VendorService {

    private final VendorRepository              vendorRepository;
    private final VendorRiskService             riskService;
    private final WorkflowEngineService         workflowEngineService;
    private final WorkflowStepRepository        stepRepository;
    private final RiskTemplateMappingRepository mappingRepository;
    private final UtilityService                utilityService;
    private final UserService                   userService;
    private final RoleRepository                roleRepository;
    private final VendorAssessmentCycleRepository cycleRepository;
    private final VendorAssessmentRepository    assessmentRepository;


    @Override
    @Transactional
    public VendorOnboardResponse onboard(VendorOnboardRequest req) {
        Long tenantId    = utilityService.getLoggedInDataContext().getTenantId();
        Long initiatedBy = utilityService.getLoggedInDataContext().getId();

        // ── 1. Save vendor ────────────────────────────────────────────
        Vendor vendor = Vendor.builder()
                .tenantId(tenantId).name(req.getName()).legalName(req.getLegalName())
                .registrationNumber(req.getRegistrationNumber()).country(req.getCountry())
                .industry(req.getIndustry()).riskClassification(req.getRiskClassification())
                .criticality(req.getCriticality()).dataAccessLevel(req.getDataAccessLevel())
                .servicesProvided(req.getServicesProvided()).website(req.getWebsite())
                .primaryContactEmail(req.getPrimaryContactEmail()).status("ONBOARDING")
                .build();
        vendorRepository.save(vendor);

        // ── 2. Calculate risk score ───────────────────────────────────
        BigDecimal riskScore = riskService.calculate(
                req.getDataAccessLevel(), req.getRiskClassification(),
                req.getCriticality(), req.getIndustry());
        vendor.setCurrentRiskScore(riskScore);
        vendorRepository.save(vendor);

        // ── 3. Start workflow ─────────────────────────────────────────
        StartWorkflowRequest wfReq = new StartWorkflowRequest();
        wfReq.setWorkflowId(req.getWorkflowId());
        wfReq.setEntityId(vendor.getId());
        wfReq.setEntityType("VENDOR");
        wfReq.setPriority("MEDIUM");
        WorkflowInstanceResponse wfResponse =
                workflowEngineService.startWorkflow(wfReq, tenantId, initiatedBy);

        // ── 4. Create VRM user + send welcome email ───────────────────
        if (req.getPrimaryContact() != null) {
            var contact = req.getPrimaryContact();
            Long vrmRoleId = roleRepository
                    .findByNameAndSide("VENDOR_VRM",
                            com.kashi.grc.usermanagement.domain.RoleSide.VENDOR)
                    .map(com.kashi.grc.usermanagement.domain.Role::getId)
                    .orElse(null);

            var userReq = new com.kashi.grc.usermanagement.dto.request.UserCreateRequest();
            userReq.setEmail(contact.getEmail());
            userReq.setFirstName(contact.getFirstName());
            userReq.setLastName(contact.getLastName());
            userReq.setJobTitle(contact.getJobTitle());
            userReq.setVendorId(vendor.getId());
            userReq.setTenantId(tenantId);
            userReq.setSendWelcomeEmail(true);
            if (vrmRoleId != null) userReq.setRoleIds(Set.of(vrmRoleId));

            try {
                userService.createUser(userReq);
                log.info("[VENDOR] VRM user created | email={}", contact.getEmail());
            } catch (Exception e) {
                log.warn("[VENDOR] VRM user creation failed | email={} | err={}",
                        contact.getEmail(), e.getMessage());
            }
        }

        // ── 5. Create assessment cycle + assign template ──────────────
        var templateMapping = mappingRepository.findByScore(riskScore);
        Map<String, Object> assignedTemplate = null;

        if (templateMapping.isPresent()) {
            var m = templateMapping.get();

            // Cycle groups all assessments for this vendor in this round
            long cycleNo = cycleRepository.countByVendorId(vendor.getId()) + 1;
            com.kashi.grc.assessment.domain.VendorAssessmentCycle cycle =
                    com.kashi.grc.assessment.domain.VendorAssessmentCycle.builder()
                            .tenantId(tenantId)
                            .vendorId(vendor.getId())
                            .cycleNo((int) cycleNo)
                            .triggeredBy(initiatedBy)
                            .triggeredAt(java.time.LocalDateTime.now())
                            .workflowInstanceId(wfResponse.getId())  // ← cycle owns the workflow
                            .status("ACTIVE")
                            .build();
            cycleRepository.save(cycle);

            com.kashi.grc.assessment.domain.VendorAssessment assessment =
                    com.kashi.grc.assessment.domain.VendorAssessment.builder()
                            .tenantId(tenantId)
                            .vendorId(vendor.getId())
                            .cycleId(cycle.getId())
                            .templateId(m.getTemplateId())
                            .status("ASSIGNED")
                            .build();
            assessmentRepository.save(assessment);

            assignedTemplate = Map.of(
                    "templateId",   m.getTemplateId(),
                    "tierLabel",    m.getTierLabel() != null ? m.getTierLabel() : "",
                    "assessmentId", assessment.getId(),
                    "cycleId",      cycle.getId());

            log.info("[VENDOR] Assessment cycle created | vendorId={} | cycleId={} | templateId={}",
                    vendor.getId(), cycle.getId(), m.getTemplateId());
        } else {
            log.warn("[VENDOR] No template mapping found | riskScore={} | tenantId={}",
                    riskScore, tenantId);
        }

        // ── 6. Build response ─────────────────────────────────────────
        var firstStep = stepRepository.findById(wfResponse.getCurrentStepId()).orElse(null);
        Map<String, Object> currentStepMap = new LinkedHashMap<>();
        currentStepMap.put("stepId",    wfResponse.getCurrentStepId());
        currentStepMap.put("stepOrder", firstStep != null ? firstStep.getStepOrder() : 1);
        currentStepMap.put("name",      firstStep != null ? firstStep.getName() : "");
        currentStepMap.put("status",    "IN_PROGRESS");

        return VendorOnboardResponse.builder()
                .vendorId(vendor.getId())
                .workflowInstanceId(wfResponse.getId())
                .calculatedRiskScore(riskScore)
                .assignedTemplate(assignedTemplate)
                .currentStep(currentStepMap)
                .build();
    }
}