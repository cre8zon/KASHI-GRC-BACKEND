package com.kashi.grc.siteassistant.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.siteassistant.dto.SiteChatDtos.*;
import com.kashi.grc.siteassistant.service.SiteAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The website assistant's public API, called by the chat widget on
 * www.kashiguard.com. No authentication: the conversation is identified by
 * the random token the browser keeps (header X-Chat-Session), which is the
 * only thing that can read it back. SecurityConfig opens exactly these three
 * routes and denies the rest of the prefix.
 */
@RestController
@RequestMapping("/v1/site-assistant/public")
@Tag(name = "Website assistant — public")
@RequiredArgsConstructor
public class SiteAssistantPublicController {

    static final String SESSION_HEADER = "X-Chat-Session";

    private final SiteAssistantService service;

    @PostMapping("/messages")
    @Operation(summary = "A visitor's message; the first reply of a new chat carries its session token")
    public ApiResponse<AskResponse> ask(@RequestBody AskRequest body,
                                        @RequestHeader(value = SESSION_HEADER, required = false) String token,
                                        HttpServletRequest http) {
        return ApiResponse.success(service.ask(body, token, clientIp(http), http.getHeader("User-Agent")));
    }

    @PostMapping("/handover")
    @Operation(summary = "Hand the chat to the team: creates an enquiry with the AI's summary")
    public ApiResponse<HandoverResponse> handover(@RequestBody HandoverRequest body,
                                                  @RequestHeader(value = SESSION_HEADER, required = false) String token,
                                                  HttpServletRequest http) {
        return ApiResponse.success(service.handover(body, token, clientIp(http), http.getHeader("User-Agent")));
    }

    @GetMapping("/session")
    @Operation(summary = "The conversation so far, including the team's replies")
    public ApiResponse<SessionView> session(@RequestHeader(value = SESSION_HEADER, required = false) String token) {
        return ApiResponse.success(service.session(token));
    }

    /** First hop in X-Forwarded-For when behind the load balancer, else the socket address. */
    static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        String real = http.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real.trim() : http.getRemoteAddr();
    }
}
