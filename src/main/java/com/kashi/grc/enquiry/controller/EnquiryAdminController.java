package com.kashi.grc.enquiry.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.enquiry.service.EnquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The platform inbox for website enquiries. Under /v1/admin/** — SecurityConfig
 * restricts the whole prefix to SIDE_SYSTEM. On top of that:
 *   platform:enquiries:view    read the inbox, an enquiry, stats, the badge
 *   platform:enquiries:manage  change status/owner/priority, add notes, link a tenant
 * so a platform role (Sales, Support) can be given exactly the inbox and nothing
 * else — the checks are on permission codes, never role names.
 *
 * Converting to a tenant is not an endpoint here: the inbox opens Create
 * Tenant pre-filled and posts enquiryId with POST /v1/tenants/provision, and
 * the enquiry is marked converted inside that transaction.
 */
@RestController
@RequestMapping("/v1/admin/enquiries")
@PreAuthorize(EnquiryAdminController.VIEW)
@Tag(name = "Enquiries — inbox")
@RequiredArgsConstructor
public class EnquiryAdminController {

    static final String VIEW   = "hasAuthority('" + EnquiryService.PERM_VIEW + "')";
    static final String MANAGE = "hasAuthority('" + EnquiryService.PERM_MANAGE + "')";

    private final EnquiryService enquiryService;
    private final UtilityService utilityService;

    @GetMapping
    @Operation(summary = "Search enquiries. ?type= and ?status= accept comma-separated lists; ?mine=true for my queue")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false, defaultValue = "false") boolean mine,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(enquiryService.list(csv(type), csv(status), q,
                mine ? actor() : null, Math.max(0, page - 1), size));
    }

    @GetMapping("/badge")
    @Operation(summary = "Count of NEW enquiries, for the sidebar badge")
    public ApiResponse<Map<String, Object>> badge() {
        return ApiResponse.success(Map.of("count", enquiryService.badge()));
    }

    @GetMapping("/stats")
    @Operation(summary = "Funnel for the last N days (default 90)")
    public ApiResponse<Map<String, Object>> stats(@RequestParam(defaultValue = "90") int days) {
        return ApiResponse.success(enquiryService.stats(days));
    }

    @GetMapping("/config")
    @Operation(summary = "Vocabularies for the inbox")
    public ApiResponse<Map<String, Object>> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("types", EnquiryService.TYPES);
        m.put("statuses", EnquiryService.STATUSES);
        m.put("priorities", EnquiryService.PRIORITIES);
        m.put("closeReasons", EnquiryService.CLOSE_REASONS);
        m.put("convertibleTypes", new TreeSet<>(EnquiryService.CONVERTIBLE));
        return ApiResponse.success(m);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.success(enquiryService.get(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Change status, priority, owner, type or plan of interest")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(enquiryService.update(id, body, actor()));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize(MANAGE)
    public ApiResponse<Map<String, Object>> note(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object text = body.get("body");
        return ApiResponse.success(enquiryService.addNote(id, text == null ? null : text.toString(), actor()));
    }

    @PostMapping("/{id}/link-tenant")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Mark converted to a tenant that already exists")
    public ApiResponse<Map<String, Object>> link(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(enquiryService.linkTenant(id, Long.valueOf(String.valueOf(body.get("tenantId"))), actor()));
    }

    private Long actor() { return utilityService.getLoggedInDataContext().getId(); }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return null;
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(String::toUpperCase).toList();
    }
}
