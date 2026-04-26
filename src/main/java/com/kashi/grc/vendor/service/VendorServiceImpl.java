package com.kashi.grc.vendor.service;

import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.usermanagement.service.user.UserService;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.dto.request.VendorOnboardRequest;
import com.kashi.grc.vendor.dto.response.VendorOnboardResponse;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.repository.WorkflowStepRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * VendorServiceImpl — handles vendor onboarding.
 *
 * Assessment cycle and template instantiation is intentionally NOT done here.
 * It is owned exclusively by ExecuteAssessmentAction, which fires automatically
 * when the "Execute Assessment Setup" SYSTEM step runs in the workflow.
 *
 * Doing it here as well caused:
 *   - Duplicate VendorAssessmentCycle rows for the same workflow_instance_id
 *   - An unsnapshotted VendorAssessment (no questions/sections) alongside the
 *     properly snapshotted one from ExecuteAssessmentAction
 *   - VendorAssessmentEntityResolver throwing NonUniqueResultException on every
 *     inbox/timeline load for that vendor
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorServiceImpl implements VendorService {

    private final VendorRepository       vendorRepository;
    private final VendorRiskService      riskService;
    private final WorkflowEngineService  workflowEngineService;
    private final WorkflowStepRepository stepRepository;
    private final UtilityService         utilityService;
    private final UserService            userService;
    private final RoleRepository         roleRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public VendorOnboardResponse onboard(VendorOnboardRequest req) {
        Long tenantId    = utilityService.getLoggedInDataContext().getTenantId();
        Long initiatedBy = utilityService.getLoggedInDataContext().getId();

        // ── 1. Save vendor ────────────────────────────────────────────
        Vendor vendor = Vendor.builder()
                .tenantId(tenantId)
                .name(req.getName())
                .legalName(req.getLegalName())
                // Normalise to null when blank so the unique index on
                // (tenant_id, registration_number) allows multiple vendors
                // without a registration number.
                .registrationNumber(
                        (req.getRegistrationNumber() != null && !req.getRegistrationNumber().isBlank())
                                ? req.getRegistrationNumber()
                                : null)
                .country(req.getCountry())
                .industry(req.getIndustry())
                .riskClassification(req.getRiskClassification())
                .criticality(req.getCriticality())
                .dataAccessLevel(req.getDataAccessLevel())
                .servicesProvided(req.getServicesProvided())
                .website(req.getWebsite())
                .primaryContactEmail(req.getPrimaryContactEmail())
                // Activate immediately on onboard — the workflow and VRM creation
                // happen in the same transaction. The vendor is operationally active
                // from the moment they're onboarded; status drives UI actions like Suspend.
                .status("ACTIVE")
                .build();
        vendorRepository.save(vendor);

        // ── 2. Calculate risk score ───────────────────────────────────
        BigDecimal riskScore = riskService.calculate(
                req.getDataAccessLevel(), req.getRiskClassification(),
                req.getCriticality(), req.getIndustry());
        vendor.setCurrentRiskScore(riskScore);
        vendorRepository.save(vendor);

        // ── 3. Create VRM user + send welcome email ───────────────────
        // MUST happen before startWorkflow() so that when assignTasksForStep()
        // resolves the VENDOR_VRM actorRole for step 2 ("VRM Acknowledges Assessment"),
        // it finds the VRM user and creates their task directly.
        // If this runs AFTER startWorkflow(), findUserIdsByRoleAndTenant returns 0
        // (same transaction, not yet visible) → fallback fires → task lands in the
        // org initiator's inbox instead of the VRM user's inbox.
        if (req.getPrimaryContact() != null) {
            var contact = req.getPrimaryContact();

            Long vrmRoleId = roleRepository
                    .findByNameAndSide("VENDOR_VRM",
                            com.kashi.grc.usermanagement.domain.RoleSide.VENDOR)
                    .map(com.kashi.grc.usermanagement.domain.Role::getId)
                    .orElse(null);

            if (vrmRoleId == null) {
                log.warn("[VENDOR] VENDOR_VRM role not found — VRM user will have no role | vendorId={}",
                        vendor.getId());
            }

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
                log.info("[VENDOR] VRM user created | email={} | vendorId={}",
                        contact.getEmail(), vendor.getId());
            } catch (Exception e) {
                log.warn("[VENDOR] VRM user creation failed | email={} | vendorId={} | err={}",
                        contact.getEmail(), vendor.getId(), e.getMessage());
            }
        }

        // ── 4. Start workflow ─────────────────────────────────────────
        // Flush the persistence context so the VRM user created above is visible
        // to findUserIdsByRoleAndVendor() inside assignTasksForStep().
        // Without this, the INSERT is pending in the JPA write-behind cache and
        // the role query returns 0 rows → fallback fires → task goes to initiator.
        entityManager.flush();

        StartWorkflowRequest wfReq = new StartWorkflowRequest();
        wfReq.setWorkflowId(req.getWorkflowId());
        wfReq.setEntityId(vendor.getId());
        wfReq.setEntityType("VENDOR");
        wfReq.setPriority("MEDIUM");
        WorkflowInstanceResponse wfResponse =
                workflowEngineService.startWorkflow(wfReq, tenantId, initiatedBy);

        // ── 5. Build response ─────────────────────────────────────────
        // NOTE: No assessment/cycle data here — ExecuteAssessmentAction owns that.
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
                .currentStep(currentStepMap)
                .build();
    }
}