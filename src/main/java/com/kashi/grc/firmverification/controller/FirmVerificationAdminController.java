package com.kashi.grc.firmverification.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.firmverification.domain.AuditFirmApplication;
import com.kashi.grc.firmverification.repository.AuditFirmApplicationRepository;
import com.kashi.grc.firmverification.service.AuditFirmVerificationService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Audit-firm verification — entirely a platform activity. A platform user
 * prepares the application from what the firm emailed, the firm confirms by a
 * one-time email link, a platform reviewer decides (a different one when
 * kashi.firm-verification.four-eyes=true).
 *
 * Platform review queue. Under /v1/admin/** — SecurityConfig restricts the
 * prefix to SIDE_SYSTEM. Decisions additionally require the permission
 * platform.firm_verification.review, so a platform user can be allowed to see
 * the queue without being allowed to decide.
 *
 * Permission codes (platform-side roles are built from these, never role names):
 *   platform.firm_verification.view     see the queue and applications (read)
 *   platform.firm_verification.prepare  open, edit, email the firm, submit for review
 *   platform.firm_verification.review   start review, request info, approve, reject, revoke
 * Any of the three lets a user read; writes need the matching one.
 *
 * Evidence is read here rather than through /v1/documents, because the
 * generic endpoints scope documents to the caller's tenant and the reviewer
 * sits in the platform tenant while the evidence belongs to the firm's.
 */
@RestController
@RequestMapping("/v1/admin/firm-verifications")
@PreAuthorize(FirmVerificationAdminController.READ)
@Tag(name = "Audit firm verification — platform review")
@RequiredArgsConstructor
public class FirmVerificationAdminController {

    private static final String REVIEW = "hasAuthority('" + AuditFirmVerificationService.PERM_REVIEW + "')";
    static final String PERM_VIEW    = "platform.firm_verification.view";
    static final String PERM_PREPARE = "platform.firm_verification.prepare";
    static final String PREPARE = "hasAuthority('" + PERM_PREPARE + "')";
    static final String READ = "hasAnyAuthority('" + PERM_VIEW + "','" + PERM_PREPARE + "','"
            + AuditFirmVerificationService.PERM_REVIEW + "')";

    private final AuditFirmVerificationService   service;
    private final AuditFirmApplicationRepository repository;
    private final TenantRepository               tenantRepository;
    private final UserRepository                 userRepository;
    private final StorageService                 storageService;
    private final UtilityService                 utilityService;

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Applications, newest first. ?status=SUBMITTED,UNDER_REVIEW")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        Set<String> st = status == null || status.isBlank() ? null
                : Arrays.stream(status.split(",")).map(String::trim).collect(Collectors.toSet());
        Map<Long, String> names = tenantRepository.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a));
        return ApiResponse.success(repository.findAllByOrderByIdDesc().stream()
                .filter(a -> st == null || st.contains(a.getStatus()))
                .map(a -> {
                    Map<String, Object> m = FirmVerificationViews.application(a, names.get(a.getTenantId()));
                    m.put("evidenceCount", service.evidence(a).size());
                    return m;
                }).toList());
    }

    @GetMapping("/tenants/{tenantId}")
    @Transactional(readOnly = true)
    @Operation(summary = "One tenant's applications and its current flag")
    public ApiResponse<Map<String, Object>> forTenant(@PathVariable Long tenantId) {
        Tenant t = tenantRepository.findById(tenantId).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isAuditFirm", t.isAuditFirm());
        m.put("applications", service.forTenant(tenantId).stream().map(this::detail).toList());
        return ApiResponse.success(m);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.success(detail(service.require(id)));
    }

    @GetMapping("/{id}/documents/{documentId}/download-url")
    @Operation(summary = "Presigned GET for one evidence file on this application (5-minute TTL)")
    public ApiResponse<Map<String, Object>> download(@PathVariable Long id, @PathVariable Long documentId) {
        AuditFirmApplication a = service.require(id);
        Document d = service.evidenceDocument(a, documentId);
        String url = storageService.generateDownloadUrl(d.getS3Key(), true, d.getFileName());
        return ApiResponse.success(Map.of("documentId", documentId, "downloadUrl", url,
                "fileName", d.getFileName(), "mimeType", d.getMimeType() == null ? "" : d.getMimeType()));
    }

    // ── Prepare ──────────────────────────────────────────────────────────────

    @PostMapping("/tenants/{tenantId}/open")
    @PreAuthorize(PREPARE)
    @Operation(summary = "Open (or resume) an application for a tenant")
    public ApiResponse<Map<String, Object>> open(@PathVariable Long tenantId) {
        return ApiResponse.success(detail(service.openDraft(tenantId, actor())));
    }

    @PutMapping("/{id}")
    @PreAuthorize(PREPARE)
    @Operation(summary = "Save the firm's details as received by email")
    public ApiResponse<Map<String, Object>> save(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(detail(service.saveDraft(service.require(id), body)));
    }

    @PostMapping("/{id}/send-confirmation")
    @PreAuthorize(PREPARE)
    @Operation(summary = "Mail the one-time confirmation link to the firm's contact (on the firm's own domain)")
    public ApiResponse<Map<String, Object>> sendConfirmation(@PathVariable Long id) {
        return ApiResponse.success(detail(service.sendConfirmation(service.require(id), actor())));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(PREPARE)
    @Operation(summary = "Submit for review — needs evidence and the firm's email confirmation")
    public ApiResponse<Map<String, Object>> submit(@PathVariable Long id) {
        return ApiResponse.success(detail(service.submit(service.require(id), actor())));
    }

    // ── Decisions ────────────────────────────────────────────────────────────

    @PostMapping("/{id}/withdraw")
    @PreAuthorize(PREPARE)
    @Operation(summary = "Withdraw an application still being prepared (opened by mistake, firm unresponsive)")
    public ApiResponse<Map<String, Object>> withdraw(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return ApiResponse.success(detail(service.withdraw(id, reason == null ? null : reason.toString(), actor())));
    }

    @PostMapping("/{id}/start-review")
    @PreAuthorize(REVIEW)
    public ApiResponse<Map<String, Object>> startReview(@PathVariable Long id) {
        return ApiResponse.success(detail(service.startReview(id, actor())));
    }

    @PostMapping("/{id}/request-info")
    @PreAuthorize(REVIEW)
    public ApiResponse<Map<String, Object>> requestInfo(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(detail(service.requestInfo(id, str(body.get("message")), actor())));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(REVIEW)
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> approve(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> checklist = body.get("checklist") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        String until = str(body.get("verifiedUntil"));
        return ApiResponse.success(detail(service.approve(id, checklist,
                until == null ? null : LocalDate.parse(until.substring(0, 10)), str(body.get("note")), actor())));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(REVIEW)
    public ApiResponse<Map<String, Object>> reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(detail(service.reject(id, str(body.get("note")), actor())));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize(REVIEW)
    public ApiResponse<Map<String, Object>> revoke(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(detail(service.revoke(id, str(body.get("reason")), actor())));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private Map<String, Object> detail(AuditFirmApplication a) {
        String name = tenantRepository.findById(a.getTenantId()).map(Tenant::getName).orElse(null);
        Map<String, Object> m = FirmVerificationViews.application(a, name);
        m.put("evidence", service.evidence(a));
        m.put("problems", service.submissionProblems(a));
        Long me = actor();
        m.put("youOpenedOrSubmitted", me.equals(a.getSubmittedBy()) || me.equals(a.getCreatedBy()));
        // True only when four-eyes is switched on and this reviewer is the preparer.
        m.put("selfReviewBlocked", service.isFourEyes()
                && (me.equals(a.getSubmittedBy()) || me.equals(a.getCreatedBy())));
        m.put("people", people(a));
        return m;
    }

    /** Names for the ids on the record, so the UI can say who did what. */
    private Map<String, String> people(AuditFirmApplication a) {
        Map<String, Long> ids = new LinkedHashMap<>();
        ids.put("createdBy", a.getCreatedBy());
        ids.put("submittedBy", a.getSubmittedBy());
        ids.put("reviewerId", a.getReviewerId());
        ids.put("decidedBy", a.getDecidedBy());
        ids.put("revokedBy", a.getRevokedBy());
        Set<Long> want = ids.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!want.isEmpty()) {
            userRepository.findAllById(want).forEach(u -> names.put(u.getId(),
                    u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName().trim() : u.getEmail()));
        }
        Map<String, String> out = new LinkedHashMap<>();
        ids.forEach((k, v) -> { if (v != null) out.put(k, names.getOrDefault(v, "User #" + v)); });
        return out;
    }

    private Long actor() { return utilityService.getLoggedInDataContext().getId(); }

    private static String str(Object o) { if (o == null) return null; String s = o.toString().trim(); return s.isEmpty() ? null : s; }
}
