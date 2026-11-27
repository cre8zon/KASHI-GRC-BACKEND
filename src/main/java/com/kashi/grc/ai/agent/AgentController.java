package com.kashi.grc.ai.agent;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * KashiGuard AI.
 *
 *   GET  /v1/ai/agent/context — the registers this user can ask about, and
 *                               suggested questions built from them
 *   POST /v1/ai/agent/ask     — one question (plus recent turns), answered
 *                               with citations; read-only
 *
 * Access is the caller's own: AgentService reads only registers their sidebar
 * shows, inside their tenant. Vendor-side users are refused — a supplier
 * contact signed in to answer a questionnaire has no business querying the
 * customer's registers, whatever their navigation happens to include.
 */
@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentService   agentService;
    private final UtilityService utilityService;

    @GetMapping("/v1/ai/agent/context")
    public ApiResponse<Map<String, Object>> context() {
        return ApiResponse.success(agentService.context(caller()));
    }

    @PostMapping("/v1/ai/agent/ask")
    public ApiResponse<AgentService.AskResponse> ask(@RequestBody AgentService.AskRequest request) {
        return ApiResponse.success(agentService.ask(request, caller()));
    }

    private User caller() {
        User user = utilityService.getLoggedInDataContext();
        if (user.getVendorId() != null) {
            throw new BusinessException("AGENT_NOT_AVAILABLE",
                    "KashiGuard AI is available to your organisation's own users.", HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
