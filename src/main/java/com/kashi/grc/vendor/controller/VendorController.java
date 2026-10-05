package com.kashi.grc.vendor.controller;

import com.kashi.grc.assessment.repository.AssessmentTemplateInstanceRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.domain.VendorContract;
import com.kashi.grc.vendor.dto.request.*;
import com.kashi.grc.vendor.dto.response.*;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.vendor.repository.VendorContractRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.vendor.service.VendorRiskService;
import com.kashi.grc.vendor.service.VendorService;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.domain.WorkflowStep;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowStepRepository;
import com.kashi.grc.workflow.repository.WorkflowStepRoleRepository;
import com.kashi.grc.workflow.repository.WorkflowStepUserRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@Tag(name = "Vendor Onboarding", description = "Vendor lifecycle, risk calculation and contracts")
@RequiredArgsConstructor
public class VendorController {

    private final VendorService              vendorService;
    private final VendorRepository           vendorRepository;
    private final VendorContractRepository   contractRepository;
    private final VendorRiskService          riskService;
    private final WorkflowEngineService      workflowEngineService;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final WorkflowStepRepository     stepRepository;
    private final WorkflowStepRoleRepository stepRoleRepository;
    private final WorkflowStepUserRepository stepUserRepository;
    private final DbRepository               dbRepository;
    private final UtilityService             utilityService;
    private final VendorAssessmentRepository assessmentRepository;
    private final VendorAssessmentCycleRepository cycleRepository;
    private final AssessmentTemplateInstanceRepository templateInstanceRepository;
    private final UserRepository userRepository;

    // 8.1 Initiate Vendor Onboarding
    @PostMapping("/v1/vendors/onboard")
    @Transactional
    @Operation(summary = "Create vendor and start workflow instance")
    public ResponseEntity<ApiResponse<VendorOnboardResponse>> onboard(
            @Valid @RequestBody VendorOnboardRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(vendorService.onboard(req)));
    }

    // 8.2 Recalculate Risk Score
    @PostMapping("/v1/vendors/{vendorId}/calculate-risk")
    @Operation(summary = "Recalculate and update vendor risk score")
    public ResponseEntity<ApiResponse<RiskScoreResponse>> calculateRisk(
            @PathVariable Long vendorId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Vendor vendor = vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));
        return ResponseEntity.ok(ApiResponse.success(riskService.calculateAndPersist(vendor)));
    }

    // 8.3 Eligible Users for Next Step
    @GetMapping("/v1/workflows/instances/{instanceId}/next-step-eligible-users")
    @Operation(summary = "Get eligible users for the next workflow step")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEligibleUsers(
            @PathVariable Long instanceId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        WorkflowInstance instance = workflowInstanceRepository.findByIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        WorkflowStep currentStep = stepRepository.findById(instance.getCurrentStepId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowStep", instance.getCurrentStepId()));
        var nextStepOpt = stepRepository.findByWorkflowIdAndStepOrder(
                instance.getWorkflowId(), currentStep.getStepOrder() + 1);
        if (nextStepOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "workflowInstanceId", instanceId,
                    "message", "No next step — workflow at final step",
                    "eligibleUsers", List.of(), "totalEligible", 0)));
        }
        WorkflowStep nextStep = nextStepOpt.get();

        // Collect direct user assignments
        List<Long> directUserIds = stepUserRepository.findByStepId(nextStep.getId())
                .stream().map(u -> u.getUserId()).toList();

        // Collect role assignments (caller resolves role→users externally)
        List<Long> roleIds = stepRoleRepository.findByStepId(nextStep.getId())
                .stream().map(r -> r.getRoleId()).toList();

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "workflowInstanceId", instanceId,
                "currentStep", Map.of("stepOrder", currentStep.getStepOrder(), "name", currentStep.getName()),
                "nextStep", Map.of("stepId", nextStep.getId(), "stepOrder", nextStep.getStepOrder(), "name", nextStep.getName()),
                "directUserIds", directUserIds,
                "roleIds", roleIds,
                "totalDirectUsers", directUserIds.size(),
                "totalRoles", roleIds.size())));
    }

    // List Vendors
    @GetMapping("/v1/vendors")
    @Operation(summary = "List vendors — paginated, filterable, sortable")
    public ResponseEntity<ApiResponse<PaginatedResponse<VendorResponse>>> listVendors(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                Vendor.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(
                        cb.equal(root.get("tenantId"), tenantId),
                        cb.isFalse(root.get("isDeleted"))
                ),
                (cb, root) -> Map.of(
                        "name",               root.get("name"),
                        "status",             root.get("status"),
                        "country",            root.get("country"),
                        "industry",           root.get("industry"),
                        "riskclassification", root.get("riskClassification")
                ),
                this::toVendorResponse
        )));
    }

    // Get Vendor
    @GetMapping("/v1/vendors/{vendorId}")
    @Operation(summary = "Get vendor by ID")
    public ResponseEntity<ApiResponse<VendorResponse>> getVendor(@PathVariable Long vendorId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Vendor v = vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));
        return ResponseEntity.ok(ApiResponse.success(toVendorResponse(v)));
    }

    @PatchMapping("/v1/vendors/{vendorId}/activate")
    @Operation(summary = "Activate vendor — ONBOARDING → ACTIVE")
    public ResponseEntity<ApiResponse<VendorResponse>> activate(@PathVariable Long vendorId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Vendor v = vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));
        v.setStatus("ACTIVE");
        vendorRepository.save(v);
        return ResponseEntity.ok(ApiResponse.success(toVendorResponse(v)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // VENDOR LIFECYCLE — EDIT AND STATUS
    //
    // Neither of these existed. That is why seed 59's vendor_detail_header
    // form pointed at PUT /v1/vendors/{id} and could only ever 404, and why
    // seeds 62 and 71 both had to deactivate Suspend and Offboard buttons:
    // they were not missing seed rows, they were missing ENDPOINTS.
    //
    // Both are additive to the vendor module, which is the module being
    // worked on — nothing outside it is touched.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Edit vendor details.
     *
     * Deliberately PARTIAL: a null field is left alone rather than nulled out.
     * The detail header form posts only the fields it renders, and a full
     * replace would wipe riskClassification, criticality and dataAccessLevel —
     * the three inputs the risk score is computed from — every time somebody
     * corrected a typo in the website.
     *
     * status is NOT editable here. It moves through the lifecycle endpoint
     * below, which validates the transition. Letting a free-text PUT set it
     * would make every guard on that endpoint decorative.
     */
    @PutMapping("/v1/vendors/{vendorId}")
    @Transactional
    @Operation(summary = "Update vendor details — partial; null fields are left unchanged")
    public ResponseEntity<ApiResponse<VendorResponse>> updateVendor(
            @PathVariable Long vendorId,
            @RequestBody Map<String, Object> body) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Vendor v = vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));

        // Blank is treated as "not supplied", not as "set to empty". A text
        // input the user never touched posts "" rather than null, and clearing
        // a vendor's country because they edited its name is not an edit.
        java.util.function.BiConsumer<String, java.util.function.Consumer<String>> set =
                (key, setter) -> {
                    Object raw = body.get(key);
                    if (raw == null) return;
                    String val = String.valueOf(raw).trim();
                    if (!val.isEmpty()) setter.accept(val);
                };

        set.accept("name",               v::setName);
        set.accept("legalName",          v::setLegalName);
        set.accept("registrationNumber", v::setRegistrationNumber);
        set.accept("country",            v::setCountry);
        set.accept("industry",           v::setIndustry);
        set.accept("website",            v::setWebsite);
        set.accept("primaryContactEmail",v::setPrimaryContactEmail);
        set.accept("servicesProvided",   v::setServicesProvided);
        set.accept("riskClassification", v::setRiskClassification);
        set.accept("criticality",        v::setCriticality);
        set.accept("dataAccessLevel",    v::setDataAccessLevel);

        vendorRepository.save(v);
        log.info("[VENDOR-UPDATE] vendorId={} | fields={} | by={}",
                vendorId, body.keySet(), utilityService.getLoggedInDataContext().getId());
        return ResponseEntity.ok(ApiResponse.success(toVendorResponse(v)));
    }

    /**
     * The lifecycle, and the only transitions allowed out of each state.
     *
     * ── TERMINATED, NOT OFFBOARDED ───────────────────────────────────────
     * I first wrote this with OFFBOARDED, which was inventing a value. The
     * codebase already has one: the VENDOR blueprint's status_flow_json (seed
     * 59) declares statuses ONBOARDING / ACTIVE / SUSPENDED / TERMINATED and
     * transitions keyed VENDOR_SUSPEND, VENDOR_REACTIVATE, VENDOR_TERMINATE,
     * and VendorDetailPage's statusColor map knows TERMINATED.
     *
     * A second terminal value would have meant a vendor whose status the
     * status-flow config does not recognise — an unstyled badge and a workflow
     * state machine that cannot see the record is finished.
     *
     * The BUTTON still says "Offboard", because that is the word for the act.
     * The stored status is TERMINATED, because that is what everything else
     * here already calls it.
     */
    private static final Map<String, Set<String>> VENDOR_TRANSITIONS = Map.of(
            "ONBOARDING", Set.of("ACTIVE", "TERMINATED"),
            "ACTIVE",     Set.of("SUSPENDED", "TERMINATED"),
            "SUSPENDED",  Set.of("ACTIVE", "TERMINATED"),
            // Terminal. A vendor you have finished with can be brought back as
            // ONBOARDING — which restarts due diligence — but never straight to
            // ACTIVE, because the assessment that justified ACTIVE is stale by
            // definition once the relationship ended.
            "TERMINATED", Set.of("ONBOARDING")
    );

    /**
     * Move a vendor through its lifecycle: suspend, offboard, reactivate.
     *
     * One endpoint rather than three, because the interesting part is the
     * transition table and three endpoints would be three copies of it. The
     * existing PATCH /activate is left alone — it is seeded, it works, and
     * ONBOARDING → ACTIVE is in the table here too.
     *
     * A reason is required for SUSPENDED and TERMINATED. Those are decisions
     * somebody will be asked about later, and "who set this to SUSPENDED and
     * why" is not answerable from a status column alone.
     */
    @PatchMapping("/v1/vendors/{vendorId}/status")
    @Transactional
    @Operation(summary = "Change vendor status — ACTIVE | SUSPENDED | TERMINATED | ONBOARDING")
    public ResponseEntity<ApiResponse<VendorResponse>> changeStatus(
            @PathVariable Long vendorId,
            @RequestBody Map<String, String> body) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        String target = body.get("status") == null ? null : body.get("status").trim().toUpperCase();
        String reason = body.get("reason") == null ? "" : body.get("reason").trim();

        if (target == null || target.isEmpty()) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "STATUS_REQUIRED", "status is required");
        }
        if (!VENDOR_TRANSITIONS.containsKey(target)) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "INVALID_STATUS",
                    "status must be one of ONBOARDING, ACTIVE, SUSPENDED, TERMINATED");
        }

        Vendor v = vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));

        String current = v.getStatus() == null ? "ONBOARDING" : v.getStatus();
        if (current.equals(target)) {
            // Idempotent rather than an error: a double-click on Suspend should
            // not produce a failure toast on an already-suspended vendor.
            return ResponseEntity.ok(ApiResponse.success(toVendorResponse(v)));
        }

        Set<String> allowed = VENDOR_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "INVALID_TRANSITION",
                    "A vendor cannot go from " + current + " to " + target
                            + ". Allowed from " + current + ": "
                            + (allowed.isEmpty() ? "nothing" : String.join(", ", allowed)));
        }

        if (("SUSPENDED".equals(target) || "TERMINATED".equals(target)) && reason.isEmpty()) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "REASON_REQUIRED",
                    "A reason is required to " + ("SUSPENDED".equals(target) ? "suspend" : "offboard")
                            + " a vendor.");
        }

        v.setStatus(target);
        vendorRepository.save(v);

        // Offboarding ends the relationship, so anything still running against
        // this vendor is cancelled with it. Leaving a live TPRM workflow on an
        // offboarded vendor means its VRM keeps receiving tasks for a vendor
        // nobody works with any more.
        if ("TERMINATED".equals(target)) {
            workflowInstanceRepository.findAllByTenantIdAndEntityTypeAndEntityIdAndStatusIn(
                            tenantId, "VENDOR", vendorId,
                            List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PENDING, WorkflowStatus.ON_HOLD))
                    .forEach(wi -> workflowEngineService.cancelInstance(
                            wi.getId(), userId, "Vendor offboarded: " + reason));
        }

        log.info("[VENDOR-STATUS] vendorId={} | {} -> {} | by={} | reason='{}'",
                vendorId, current, target, userId, reason);
        return ResponseEntity.ok(ApiResponse.success(toVendorResponse(v)));
    }

    // Create Contract
    @PostMapping("/v1/vendors/{vendorId}/contracts")
    @Operation(summary = "Create a contract for a vendor")
    public ResponseEntity<ApiResponse<ContractResponse>> createContract(
            @PathVariable Long vendorId,
            @Valid @RequestBody ContractCreateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));
        VendorContract c = VendorContract.builder()
                .tenantId(tenantId).vendorId(vendorId)
                .contractNumber(req.getContractNumber()).contractType(req.getContractType())
                .startDate(req.getStartDate()).endDate(req.getEndDate()).renewalDate(req.getRenewalDate())
                .contractValue(req.getContractValue())
                .status(req.getStatus() != null ? req.getStatus() : "ACTIVE")
                .documentId(req.getDocumentId())
                .build();
        contractRepository.save(c);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toContractResponse(c)));
    }

    @PostMapping("/v1/vendors/{vendorId}/restart-workflow")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> restartWorkflow(
            @PathVariable Long vendorId,
            // ── QUERY PARAM **OR** BODY ──────────────────────────────────────
            //
            // This was @RequestParam Long workflowId, required. vendors.api.js
            // sends it as a query param and works; a DB-driven form action
            // sends a JSON body and got
            //
            //   MissingServletRequestParameterException: Required request
            //   parameter 'workflowId' ... is not present
            //
            // rendered to the user as "An unexpected error occurred".
            //
            // Widened rather than changed: the query param still works exactly
            // as before, so the existing JSX page is untouched, and a body is
            // now accepted too. Any caller that can express one or the other
            // works, which is the point of a contract that two different UIs
            // have to satisfy.
            @RequestParam(required = false) Long workflowId,
            @RequestBody(required = false) Map<String, Object> body) {

        if (workflowId == null && body != null && body.get("workflowId") != null) {
            try {
                workflowId = Long.valueOf(String.valueOf(body.get("workflowId")).trim());
            } catch (NumberFormatException e) {
                throw new com.kashi.grc.common.exception.BusinessException(
                        "INVALID_WORKFLOW_ID",
                        "workflowId must be a number, received: " + body.get("workflowId"));
            }
        }
        if (workflowId == null) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "WORKFLOW_ID_REQUIRED",
                    "workflowId is required — pass it as a query parameter or in the request body.");
        }

        Long tenantId    = utilityService.getLoggedInDataContext().getTenantId();
        Long initiatedBy = utilityService.getLoggedInDataContext().getId();

        Vendor v = vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId));

        // ── Guard: block restart if assessment already instantiated ───
        boolean assessmentInstantiated = cycleRepository
                .findByVendorIdOrderByCycleNo(vendorId).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .reduce((a, b) -> b)
                .map(cycle -> !assessmentRepository.findByCycleId(cycle.getId()).isEmpty())
                .orElse(false);

        if (assessmentInstantiated) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "ASSESSMENT_ALREADY_INSTANTIATED",
                    "Cannot restart workflow — assessment already instantiated for this cycle. " +
                            "Close the current cycle before starting a new workflow.");
        }

        // Cancel any existing active instances via the engine — this nulls currentStepId,
        // marks step instances REJECTED, and expires pending tasks so the VRM's inbox
        // is clean and stale tasks don't cause INSTANCE_NOT_ACTIVE errors.
        workflowInstanceRepository.findAllByTenantIdAndEntityTypeAndEntityIdAndStatusIn(
                        tenantId, "VENDOR", vendorId,
                        List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PENDING, WorkflowStatus.ON_HOLD))
                .forEach(wi -> workflowEngineService.cancelInstance(
                        wi.getId(), initiatedBy, "Cancelled to restart workflow"));

        // Start fresh workflow
        StartWorkflowRequest wfReq = new StartWorkflowRequest();
        wfReq.setWorkflowId(workflowId);
        wfReq.setEntityId(vendorId);
        wfReq.setEntityType("VENDOR");
        wfReq.setPriority("MEDIUM");
        WorkflowInstanceResponse wfResponse =
                workflowEngineService.startWorkflow(wfReq, tenantId, initiatedBy);

        // Find active cycle OR create one if none exists
        var activeCycle = cycleRepository.findByVendorIdOrderByCycleNo(vendorId)
                .stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .reduce((a, b) -> b)
                .orElse(null);

        if (activeCycle != null) {
            // Update existing cycle
            activeCycle.setWorkflowInstanceId(wfResponse.getId());
            cycleRepository.save(activeCycle);
        } else {
            // No cycle exists yet — create one now
            long cycleNo = cycleRepository.countByVendorId(vendorId) + 1;
            com.kashi.grc.assessment.domain.VendorAssessmentCycle cycle =
                    com.kashi.grc.assessment.domain.VendorAssessmentCycle.builder()
                            .tenantId(tenantId)
                            .vendorId(vendorId)
                            .cycleNo((int) cycleNo)
                            .triggeredAt(java.time.LocalDateTime.now())
                            .triggeredBy(initiatedBy)
                            .workflowInstanceId(wfResponse.getId())
                            .status("ACTIVE")
                            .build();
            cycleRepository.save(cycle);
        }

        // ── Map.of FORBIDS NULL VALUES ───────────────────────────────────────
        //
        // This is the "An unexpected error occurred" on Start workflow.
        // Map.of throws NullPointerException the moment any value is null, and
        // currentStepId is null whenever the first step is asynchronous or has
        // not been claimed yet — which for the TPRM blueprint is the normal
        // case, not an edge one.
        //
        // The damage was entirely in the response. By the time this line runs
        // the old instances have been cancelled, the new one is started and the
        // cycle is pointed at it — all committed. So the workflow restarted
        // correctly and the screen said it had failed, which is the worst of
        // both: the user retries, and the retry hits the
        // ASSESSMENT_ALREADY_INSTANTIATED guard or cancels a workflow that was
        // fine.
        //
        // LinkedHashMap accepts nulls. Same keys, same order, no throw.
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("workflowInstanceId", wfResponse.getId());
        out.put("currentStepId",      wfResponse.getCurrentStepId());
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // List Contracts
    @GetMapping("/v1/vendors/{vendorId}/contracts")
    @Operation(summary = "List contracts for a vendor — paginated, filterable")
    public ResponseEntity<ApiResponse<PaginatedResponse<ContractResponse>>> listContracts(
            @PathVariable Long vendorId,
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                VendorContract.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(
                        cb.equal(root.get("tenantId"), tenantId),
                        cb.equal(root.get("vendorId"), vendorId)
                ),
                (cb, root) -> Map.of(
                        "contractnumber", root.get("contractNumber"),
                        "contracttype",   root.get("contractType"),
                        "status",         root.get("status")
                ),
                this::toContractResponse
        )));
    }

    // Update Contract
    @PutMapping("/v1/vendors/{vendorId}/contracts/{contractId}")
    @Operation(summary = "Update a vendor contract")
    public ResponseEntity<ApiResponse<ContractResponse>> updateContract(
            @PathVariable Long vendorId, @PathVariable Long contractId,
            @RequestBody ContractUpdateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        VendorContract c = contractRepository.findByIdAndTenantId(contractId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorContract", contractId));
        if (req.getStatus()        != null) c.setStatus(req.getStatus());
        if (req.getEndDate()       != null) c.setEndDate(req.getEndDate());
        if (req.getRenewalDate()   != null) c.setRenewalDate(req.getRenewalDate());
        if (req.getContractValue() != null) c.setContractValue(req.getContractValue());
        contractRepository.save(c);
        return ResponseEntity.ok(ApiResponse.success(toContractResponse(c)));
    }

    // ── Mappers ────────────────────────────────────────────────────
    private VendorResponse toVendorResponse(Vendor v) {
        VendorResponse.VendorResponseBuilder builder = VendorResponse.builder()
                // id mirrors vendorId — the generic list screen navigates on row.id.
                .id(v.getId())
                .vendorId(v.getId()).name(v.getName()).legalName(v.getLegalName())
                .country(v.getCountry()).industry(v.getIndustry()).status(v.getStatus())
                .riskClassification(v.getRiskClassification()).criticality(v.getCriticality())
                .dataAccessLevel(v.getDataAccessLevel()).currentRiskScore(v.getCurrentRiskScore())
                .primaryContactEmail(v.getPrimaryContactEmail()).website(v.getWebsite())
                .createdAt(v.getCreatedAt());

        // Attach active cycle's workflow info
// In toVendorResponse() — add templateInstantiated flag
        cycleRepository.findByVendorIdOrderByCycleNo(v.getId()).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .reduce((a, b) -> b)
                .ifPresent(c -> {
                    builder.activeCycleId(c.getId())
                            .activeWorkflowInstanceId(c.getWorkflowInstanceId())
                            .currentCycleNo(c.getCycleNo());

                    // Populate workflowInstanceStatus so the frontend can distinguish
                    // an actively-running workflow from a cancelled/completed one.
                    // Without this, vendor.workflowInstanceStatus is always null,
                    // causing hasWorkflow = false and the setup banner never hiding.
                    if (c.getWorkflowInstanceId() != null) {
                        workflowInstanceRepository.findById(c.getWorkflowInstanceId())
                                .ifPresent(wi -> builder.workflowInstanceStatus(wi.getStatus().name()));
                    }

                    // Check if template was instantiated for this cycle's assessment
                    boolean instantiated = assessmentRepository.findByCycleId(c.getId())
                            .stream()
                            .anyMatch(a -> templateInstanceRepository.findByAssessmentId(a.getId()).isPresent());
                    builder.assessmentInstantiated(instantiated);  // ← ADD to VendorResponse
                });

        // VRM user — first user with this vendorId
        userRepository.findByVendorIdAndIsDeletedFalse(v.getId())
                .stream().findFirst()
                .ifPresent(u -> builder.vrmUserId(u.getId()));

        return builder.build();
    }

    private ContractResponse toContractResponse(VendorContract c) {
        return ContractResponse.builder()
                .contractId(c.getId()).vendorId(c.getVendorId())
                .contractNumber(c.getContractNumber()).contractType(c.getContractType())
                .startDate(c.getStartDate()).endDate(c.getEndDate()).renewalDate(c.getRenewalDate())
                .contractValue(c.getContractValue()).status(c.getStatus())
                .documentId(c.getDocumentId()).createdAt(c.getCreatedAt()).build();
    }
}