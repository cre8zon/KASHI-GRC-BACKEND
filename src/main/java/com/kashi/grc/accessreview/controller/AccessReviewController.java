package com.kashi.grc.accessreview.controller;

import com.kashi.grc.accessreview.domain.AccessReviewCampaign;
import com.kashi.grc.accessreview.domain.AccessReviewItem;
import com.kashi.grc.accessreview.repository.AccessReviewItemRepository;
import com.kashi.grc.accessreview.service.AccessReviewService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/v1/access-reviews")
@RequiredArgsConstructor
@Tag(name = "Access reviews", description = "User access certification campaigns")
public class AccessReviewController {

    private final AccessReviewService        service;
    private final AccessReviewItemRepository itemRepository;
    private final UtilityService             utilityService;
    private final DbRepository               dbRepository;

    // ── Campaigns ─────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Campaigns")
    public ResponseEntity<ApiResponse<Object>> list(@RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AccessReviewCampaign.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    preds.add(cb.isFalse(root.get("isDeleted")));
                    String status = allParams.get("status");
                    if (status != null && !status.isBlank()) {
                        preds.add(cb.equal(root.get("status"),
                                AccessReviewCampaign.Status.valueOf(status)));
                    }

                    // ?mine=true — campaigns where the caller has something to
                    // decide. Without this the two nav entries showed an
                    // identical list, which made "My access reviews" look
                    // broken rather than empty.
                    //
                    // A subquery rather than a join: a campaign has many items
                    // and joining would return it once per item of mine.
                    if ("true".equalsIgnoreCase(allParams.get("mine"))) {
                        Long me = utilityService.getLoggedInDataContext().getId();
                        var sub = cb.createQuery().subquery(Long.class);
                        var item = sub.from(AccessReviewItem.class);
                        sub.select(item.get("campaignId"))
                                .where(cb.and(
                                        cb.equal(item.get("reviewerUserId"), me),
                                        cb.equal(item.get("decision"), AccessReviewItem.Decision.PENDING)));
                        preds.add(root.get("id").in(sub));
                    }
                    return preds;
                },
                // Five arguments, not four. Omitting this map binds the row
                // mapper to this slot and every getter then resolves against
                // CriteriaBuilder — which is how ExceptionController failed.
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> f = new HashMap<>();
                    f.put("name",         root.get("name"));
                    f.put("campaign_ref", root.get("campaignRef"));
                    f.put("campaignref",  root.get("campaignRef"));
                    f.put("status",       root.get("status"));
                    f.put("scope_type",   root.get("scopeType"));
                    f.put("due_at",       root.get("dueAt"));
                    f.put("created_at",   root.get("createdAt"));
                    return f;
                },
                c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          c.getId());
                    m.put("campaignRef", c.getCampaignRef());
                    m.put("name",        c.getName());
                    m.put("scopeType",   c.getScopeType());
                    m.put("status",      c.getStatus());
                    m.put("periodStart", c.getPeriodStart());
                    m.put("periodEnd",   c.getPeriodEnd());
                    m.put("dueAt",       c.getDueAt());
                    m.put("completedAt", c.getCompletedAt());
                    m.put("editable",    c.getStatus() == AccessReviewCampaign.Status.DRAFT);
                    return m;
                })));
    }

    /**
     * One campaign.
     *
     * Missing entirely from the first version, which is why the detail page
     * showed "Could not load this page": UniversalModulePage fetches
     * GET {apiBasePath}/{id} for every module, and there was nothing to call.
     * The list, launch and complete endpoints all existed, so the module looked
     * finished right up to the moment somebody clicked a row.
     */
    @GetMapping("/{id}")
    @Operation(summary = "One campaign, with its progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        AccessReviewCampaign c = service.getCampaign(id, tenantId);
        List<AccessReviewItem> items = itemRepository.findByCampaignId(id);

        long decided = items.stream()
                .filter(i -> i.getDecision() != AccessReviewItem.Decision.PENDING).count();
        long blocking = items.stream().filter(AccessReviewItem::isBlockingCompletion).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          c.getId());
        m.put("campaignRef", c.getCampaignRef());
        m.put("name",        c.getName());
        m.put("description", c.getDescription());
        m.put("scopeType",   c.getScopeType());
        m.put("status",      c.getStatus());
        m.put("periodStart", c.getPeriodStart());
        m.put("periodEnd",   c.getPeriodEnd());
        m.put("dueAt",       c.getDueAt());
        m.put("launchedAt",  c.getLaunchedAt());
        m.put("completedAt", c.getCompletedAt());
        m.put("summaryJson", c.getSummaryJson());
        m.put("totalItems",  items.size());
        m.put("decidedItems", decided);
        // What is standing between this campaign and completion, so the header
        // can say so before somebody presses Complete and gets refused.
        m.put("blockingItems", blocking);
        m.put("progressPercent", items.isEmpty() ? 0
                : (int) Math.round(decided * 100.0 / items.size()));
        m.put("editable",    c.getStatus() == AccessReviewCampaign.Status.DRAFT);
        return ResponseEntity.ok(ApiResponse.success(m));
    }

    @PostMapping
    @Operation(summary = "Create a campaign")
    public ResponseEntity<ApiResponse<AccessReviewCampaign>> create(@RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.create(body, ctx.getId(), ctx.getTenantId())));
    }

    /** Freezes the population and generates the items. */
    @PostMapping("/{id}/launch")
    @Operation(summary = "Launch — sweeps roles, direct permissions, firm grants and odd accounts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> launch(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        int n = service.launch(id, tenantId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("itemsCreated", n)));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Close it — refuses while any change is unconfirmed")
    public ResponseEntity<ApiResponse<AccessReviewCampaign>> complete(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.complete(id, ctx.getId(), ctx.getTenantId())));
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/linked-items")
    @Operation(summary = "Everything in this campaign")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> items(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                itemRepository.findByCampaignId(id).stream()
                        .filter(i -> tenantId.equals(i.getTenantId()))
                        .map(this::toRow).toList()));
    }

    /**
     * The caller's own queue.
     *
     * The only list most people will ever open — a manager has eleven rows to
     * decide, not four hundred. Strictly own-only, the same correction the
     * training module needed when training:report widened My Training to
     * everybody's assignments.
     */
    @GetMapping("/my-items")
    @Operation(summary = "Items assigned to you to decide")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myItems() {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                itemRepository.findByTenantIdAndReviewerUserId(ctx.getTenantId(), ctx.getId())
                        .stream().map(this::toRow).toList()));
    }

    @PostMapping("/items/{itemId}/decide")
    @Operation(summary = "RETAIN, REVOKE or MODIFY")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(
            @PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(toRow(service.decide(
                itemId,
                String.valueOf(body.get("decision")),
                body.get("remarks") == null ? null : String.valueOf(body.get("remarks")),
                ctx.getId(), ctx.getTenantId()))));
    }

    /** Proof the change was made — a separate permission, usually a separate person. */
    @PostMapping("/items/{itemId}/confirm-revocation")
    @Operation(summary = "Confirm the access was actually removed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
            @PathVariable Long itemId, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        boolean ok = body == null || !Boolean.FALSE.equals(body.get("succeeded"));
        return ResponseEntity.ok(ApiResponse.success(toRow(service.confirmRevocation(
                itemId, ok,
                body == null || body.get("remarks") == null ? null : String.valueOf(body.get("remarks")),
                ctx.getId(), ctx.getTenantId()))));
    }

    @GetMapping("/stats")
    @Operation(summary = "Counts for the dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.getStats(tenantId)));
    }

    private Map<String, Object> toRow(AccessReviewItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               i.getId());
        m.put("title",            i.getSubjectLabel());
        m.put("subjectLabel",     i.getSubjectLabel());
        m.put("entitlementType",  i.getEntitlementType());
        m.put("entitlementLabel", i.getEntitlementLabel());
        m.put("decision",         i.getDecision());
        m.put("decisionNote",     i.getDecisionNote());
        m.put("revocationStatus", i.getRevocationStatus());
        m.put("reviewerUserId",   i.getReviewerUserId());
        m.put("flags",            i.getFlagsJson());
        m.put("decidedAt",        i.getDecidedAt());
        // A row still blocking completion, computed rather than stored so it
        // stays right after a confirmation lands.
        m.put("blocking",         i.isBlockingCompletion());
        m.put("editable",         i.getDecision() == AccessReviewItem.Decision.PENDING);
        return m;
    }
}