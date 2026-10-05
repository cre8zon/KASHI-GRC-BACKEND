package com.kashi.grc.vendor.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.usermanagement.service.user.UserService;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.dto.request.VendorOnboardRequest;
import com.kashi.grc.vendor.dto.response.VendorOnboardResponse;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.workflow.domain.Workflow;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.repository.WorkflowRepository;
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
    private final WorkflowRepository     workflowRepository;
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
        wfReq.setWorkflowId(resolveWorkflowId(req.getWorkflowId(), tenantId));
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

    /**
     * Which TPRM workflow to start when the caller did not name one.
     *
     * ── ISSUE 7 ───────────────────────────────────────────────────────────
     * The onboarding form asked for a workflow, and the flow asked again for
     * the assessment template once the vendor existed. The workflowId is now
     * optional on the request (see VendorOnboardRequest) and the picker comes
     * off the form; the template choice, which happens after the risk score is
     * calculated and with the candidate list in hand, is the ask that can
     * actually be answered.
     *
     * ── RESOLUTION ORDER ──────────────────────────────────────────────────
     *   1. What the caller sent, if anything. An explicit choice always wins —
     *      the API keeps working for anyone who still makes one.
     *   2. The tenant's own active VENDOR workflow. A tenant that has authored
     *      its own blueprint means it, and the global one is not a substitute.
     *   3. The global active VENDOR workflow.
     *
     * Within 2 and 3, highest version wins. Not "the only one" — that would
     * throw the moment a second version is published, which is a normal
     * administrative act and should not break onboarding. Highest version is
     * also what findTopBy…OrderByVersionDesc already means elsewhere in this
     * repository, so it is the convention rather than a new rule.
     *
     * ── AND IT REFUSES RATHER THAN GUESSES ────────────────────────────────
     * If nothing resolves, this throws with a message naming what is missing.
     * The alternative — onboarding "succeeding" with no workflow — produces a
     * vendor with no assessment, no tasks and nothing in anybody's inbox, and
     * the failure surfaces days later as "why did nothing happen", far from
     * the cause.
     */
    private Long resolveWorkflowId(Long requested, Long tenantId) {
        if (requested != null) return requested;

        Long tenantOwned = highestVersion(
                workflowRepository.findByTenantIdAndEntityTypeAndIsActiveTrue(tenantId, "VENDOR"));
        if (tenantOwned != null) {
            log.info("[VENDOR] No workflowId supplied — using tenant's own VENDOR workflow | id={} | tenant={}",
                    tenantOwned, tenantId);
            return tenantOwned;
        }

        Long global = highestVersion(
                workflowRepository.findByTenantIdIsNullAndEntityTypeAndIsActiveTrue("VENDOR"));
        if (global != null) {
            log.info("[VENDOR] No workflowId supplied — using global VENDOR workflow | id={}", global);
            return global;
        }

        throw new BusinessException("NO_VENDOR_WORKFLOW",
                "No active VENDOR workflow is published, so onboarding cannot start one. "
                        + "Publish a TPRM workflow blueprint, or pass workflowId explicitly.");
    }

    private Long highestVersion(java.util.List<Workflow> candidates) {
        return candidates.stream()
                .max(java.util.Comparator.comparing(Workflow::getVersion,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .map(Workflow::getId)
                .orElse(null);
    }
}