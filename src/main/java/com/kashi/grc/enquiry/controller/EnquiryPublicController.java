package com.kashi.grc.enquiry.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.enquiry.dto.EnquirySubmitRequest;
import com.kashi.grc.enquiry.service.EnquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The website's forms post here: demo, trial, contact sales, audit-firm
 * application, partnership, support. No authentication — see EnquiryService
 * for the honeypot and rate limit. SecurityConfig opens only this POST.
 */
@RestController
@RequestMapping("/v1/enquiries/public")
@Tag(name = "Enquiries — public")
@RequiredArgsConstructor
public class EnquiryPublicController {

    private final EnquiryService enquiryService;

    @PostMapping
    @Operation(summary = "Submit an enquiry from the website (no auth)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(@RequestBody EnquirySubmitRequest body,
                                                                   HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                enquiryService.submit(body, clientIp(http), http.getHeader("User-Agent"))));
    }

    /** First hop in X-Forwarded-For when behind the load balancer, else the socket address. */
    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        String real = http.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real.trim() : http.getRemoteAddr();
    }
}
