package com.kashi.grc.inbox.controller;

import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One count for one inbox.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────
 * The sidebar badge pointed at /v1/workflow-instances/tasks/user/me/count,
 * which counts workflow tasks and nothing else. That was correct while the
 * inbox showed only tasks. It now shows action items too, so the badge
 * under-reported: a contributor with seven assigned questions and no workflow
 * task saw no badge at all, and the page header said "0 tasks pending" above
 * seven rows of work.
 *
 * A ui_navigation row takes ONE badge endpoint, so the choice was either a
 * badge that means something narrower than the page it sits on, or one endpoint
 * that answers for the page. This is that endpoint.
 *
 * ── WHY IT IS A SUM AND NOT A UNION QUERY ────────────────────────────────
 * It calls the two counts that already exist rather than joining the tables.
 * Tasks and action items have different scoping rules — task counts filter on
 * live workflow instances, action items filter on status, assignment and group
 * role — and a hand-written union would be a third definition of "open",
 * drifting from both the moment either changes. Two cheap counts and an
 * addition stays correct by construction.
 *
 * The breakdown is returned alongside the total so a caller that wants the two
 * numbers does not need a second round trip. The sidebar reads `total`, which
 * is the field its badge hook already looks for.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Inbox", description = "Combined counts for the unified inbox")
public class InboxController {

    private final UtilityService         utilityService;
    private final TaskInstanceRepository taskInstanceRepository;
    private final ActionItemService      actionItemService;

    /**
     * GET /v1/inbox/my/count
     *
     * { "tasks": 3, "actionItems": 7, "total": 10 }
     */
    @GetMapping("/v1/inbox/my/count")
    @Operation(summary = "How many things are waiting for me — tasks plus action items")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myCount() {
        User user = utilityService.getLoggedInDataContext();

        long tasks = taskInstanceRepository.countActiveTasksOnLiveInstances(user.getId());
        // Same rules as the list the inbox page shows (/v1/action-items/my):
        // assigned to me, to one of my roles or my vendor, or awaiting my review.
        java.util.List<String> roles = user.getRoles() == null ? java.util.List.of()
                : user.getRoles().stream().map(r -> r.getName()).filter(n -> n != null && !n.isEmpty()).toList();
        long items = actionItemService.countMyOpenItems(user.getId(), roles, user.getTenantId(), user.getVendorId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks",       tasks);
        out.put("actionItems", items);
        out.put("total",       tasks + items);
        return ResponseEntity.ok(ApiResponse.success(out));
    }
}