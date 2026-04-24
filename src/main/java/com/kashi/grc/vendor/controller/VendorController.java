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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            @RequestParam Long workflowId) {
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

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "workflowInstanceId", wfResponse.getId(),
                "currentStepId",      wfResponse.getCurrentStepId()
        )));
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